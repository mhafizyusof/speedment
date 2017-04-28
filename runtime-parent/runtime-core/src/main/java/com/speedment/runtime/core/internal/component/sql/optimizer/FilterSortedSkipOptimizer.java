/**
 *
 * Copyright (c) 2006-2017, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.runtime.core.internal.component.sql.optimizer;

import com.speedment.runtime.config.identifier.ColumnIdentifier;
import com.speedment.runtime.core.component.sql.Metrics;
import com.speedment.runtime.core.component.sql.SqlStreamOptimizer;
import com.speedment.runtime.core.component.sql.SqlStreamOptimizerInfo;
import com.speedment.runtime.core.db.AsynchronousQueryResult;
import com.speedment.runtime.core.db.DbmsType;
import static com.speedment.runtime.core.db.DbmsType.SkipLimitSupport.NONE;
import static com.speedment.runtime.core.db.DbmsType.SkipLimitSupport.ONLY_AFTER_SORTED;
import com.speedment.runtime.core.internal.stream.builder.action.reference.FilterAction;
import com.speedment.runtime.core.internal.stream.builder.action.reference.LimitAction;
import com.speedment.runtime.core.internal.stream.builder.action.reference.SkipAction;
import com.speedment.runtime.core.internal.stream.builder.action.reference.SortedComparatorAction;
import com.speedment.runtime.core.internal.stream.builder.streamterminator.StreamTerminatorUtil;
import com.speedment.runtime.core.internal.stream.builder.streamterminator.StreamTerminatorUtil.RenderResult;
import static com.speedment.runtime.core.internal.stream.builder.streamterminator.StreamTerminatorUtil.isContainingOnlyFieldPredicate;
import static com.speedment.runtime.core.internal.stream.builder.streamterminator.StreamTerminatorUtil.isSortedActionWithFieldPredicate;
import com.speedment.runtime.core.stream.Pipeline;
import com.speedment.runtime.core.stream.action.Action;
import com.speedment.runtime.field.comparator.FieldComparator;
import com.speedment.runtime.field.predicate.FieldPredicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import static java.util.Objects.requireNonNull;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static java.util.stream.Collectors.toList;

/**
 * This Optimizer takes care of the following case:
 * <br>
 * <ul>
 * <li> a) Zero or more filter() operations
 * <li> b) Zero or more sorted() operations
 * <li> c) Zero or more skip() operations
 * <li> d) Zero or more limit() operations
 *
 * <em>No other operations</em> must be in the sequence a-d or within the
 * individual items a-d. <em>All</em> parameters in a and b must be obtained via
 * fields. Failure to any of these rules will make the Optimizer reject
 * optimization. Steps a) and b) may swap places.
 *
 * Thus, this optimizer can handle a (FILTER*, SORTED*, SKIP*, LIMIT*) or
 * (SORTED*, LIMIT*, SKIP*, LIMIT*) pattern where all non-primitive parameters
 * are all Field derived
 *
 *
 * @author Per Minborg
 * @param <ENTITY> entity type
 */
public final class FilterSortedSkipOptimizer<ENTITY> implements SqlStreamOptimizer<ENTITY> {

    private final FilterOperation FILTER_OPERATION = new FilterOperation();
    private final SortedOperation SORTED_OPERATION = new SortedOperation();
    private final SkipOperation SKIP_OPERATION = new SkipOperation();
    private final LimitOperation LIMIT_OPERATION = new LimitOperation();

    private final List<Operation<ENTITY>> FILTER_SORTED_SKIP_PATH = Arrays.asList(
        FILTER_OPERATION,
        SORTED_OPERATION,
        SKIP_OPERATION,
        LIMIT_OPERATION
    );
    private final List<Operation<ENTITY>> SORTED_FILTER_SKIP_PATH = Arrays.asList(
        SORTED_OPERATION,
        FILTER_OPERATION,
        SKIP_OPERATION,
        LIMIT_OPERATION
    );

    // FILTER <-> SORTED
    // This optimizer can handle a (FILTER*,SORTED*,SKIP*, LIMIT*) pattern where filter and sorted parameters are all Field derived
    @Override
    public Metrics metrics(Pipeline initialPipeline, DbmsType dbmsType) {
        requireNonNull(initialPipeline);
        requireNonNull(dbmsType);
        final DbmsType.SkipLimitSupport skipLimitSupport = dbmsType.getSkipLimitSupport();
        final AtomicInteger filterCounter = new AtomicInteger();
        final AtomicInteger orderCounter = new AtomicInteger();
        final AtomicInteger skipCounter = new AtomicInteger();
        final AtomicInteger limitCounter = new AtomicInteger();

        traverse(initialPipeline,
            $ -> filterCounter.incrementAndGet(),
            $ -> orderCounter.incrementAndGet(),
            $ -> skipCounter.incrementAndGet(),
            $ -> limitCounter.incrementAndGet()
        );

        if (skipLimitSupport == ONLY_AFTER_SORTED && orderCounter.get() == 0) {
            // Just decline. There are other optimizer that handles just filtering better
            return Metrics.empty();
        }
        if (skipLimitSupport == NONE) {
            return Metrics.of(filterCounter.get() + orderCounter.get());
        }

        return Metrics.of(filterCounter.get() + orderCounter.get() + skipCounter.get() + limitCounter.get());
    }

    @Override
    public <P extends Pipeline> P optimize(
        final P initialPipeline,
        final SqlStreamOptimizerInfo<ENTITY> info,
        final AsynchronousQueryResult<ENTITY> query
    ) {
        requireNonNull(initialPipeline);
        requireNonNull(info);
        requireNonNull(query);
        final DbmsType.SkipLimitSupport skipLimitSupport = info.getDbmsType().getSkipLimitSupport();
        final List<FilterAction<ENTITY>> filters = new ArrayList<>();
        final List<SortedComparatorAction<ENTITY>> sorteds = new ArrayList<>();
        final List<SkipAction<ENTITY>> skips = new ArrayList<>();
        final List<LimitAction<ENTITY>> limits = new ArrayList<>();

        traverse(initialPipeline, filters::add, sorteds::add, skips::add, limits::add);

        final List<Object> values = new ArrayList<>();
        final StringBuilder sql = new StringBuilder();

        sql.append(info.getSqlSelect());

        if (!filters.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Predicate<ENTITY>> predicates = filters.stream()
                .map(FilterAction::getPredicate)
                .map(p -> (Predicate<ENTITY>) p)
//                .map(p -> (FieldPredicate<ENTITY>) p)
                .collect(toList());

            final RenderResult rr = StreamTerminatorUtil.renderSqlWhere(
                info.getDbmsType(),
                info.getSqlColumnNamer(),
                info.getSqlDatabaseTypeFunction(),
                predicates
            );

//            final FieldPredicateView spv = info.getDbmsType().getFieldPredicateView();
//
//            // Todo: Allow composite predicates
//            @SuppressWarnings("unchecked")
//            final List<SqlPredicateFragment> fragments = filters.stream()
//                .map(f -> f.getPredicate())
//                .map(p -> (FieldPredicate<ENTITY>) p)
//                .map(sp -> spv.transform(info.getSqlColumnNamer(), info.getSqlDatabaseTypeFunction(), sp))
//                .collect(toList());
//
//            // Todo: Make this in one sweep
//            String expression = fragments.stream()
//                .map(SqlPredicateFragment::getSql)
//                .collect(joining(" AND "));
//            sql.append(" WHERE ").append(expression);
//
//            for (int i = 0; i < fragments.size(); i++) {
//
//                @SuppressWarnings("unchecked")
//                final FieldPredicate<ENTITY> p = (FieldPredicate<ENTITY>) filters.get(i).getPredicate();
//                final Field<ENTITY> referenceFieldTrait = p.getField();
//
//                @SuppressWarnings("unchecked")
//                final TypeMapper<Object, Object> tm = (TypeMapper<Object, Object>) referenceFieldTrait.typeMapper();
//
//                fragments.get(i).objects()
//                    .map(tm::toDatabaseType)
//                    .forEachOrdered(values::add);
//
//            }
            sql.append(" WHERE ").append(rr.getSql());
            values.addAll(rr.getValues());

        }

        if (!sorteds.isEmpty()) {
            sql.append(" ORDER BY ");
            // Iterate backwards
            final Set<ColumnIdentifier<ENTITY>> columns = new HashSet<>();
            for (int i = sorteds.size() - 1; i >= 0; i--) {
                final SortedComparatorAction<ENTITY> sortedAction = sorteds.get(i);
                @SuppressWarnings("unchecked")
                final FieldComparator<ENTITY, ?> fieldComparator = (FieldComparator<ENTITY, ?>) sortedAction.getComparator();
                final ColumnIdentifier<ENTITY> columnIdentifier = fieldComparator.getField().identifier();

                // SQL Server only allows distict columns in ORDER BY 
                if (columns.add(columnIdentifier)) {
                    if (!(i == (sorteds.size() - 1))) {
                        sql.append(", ");
                    }

                    boolean isReversed = fieldComparator.isReversed();
                    String fieldName = info.getSqlColumnNamer().apply(fieldComparator.getField());
                    sql.append(fieldName);
                    if (isReversed) {
                        sql.append(" DESC");
                    } else {
                        sql.append(" ASC");
                    }
                }
            }
        }

        final String finalSql;
        if (skipLimitSupport == NONE) {
            finalSql = sql.toString();
            initialPipeline.removeIf(a -> filters.contains(a) || sorteds.contains(a));
        } else {
            final long sumSkip = skips.stream().mapToLong(SkipAction::getSkip).sum();
            final long minLimit = limits.stream().mapToLong(LimitAction::getLimit).min().orElse(Long.MAX_VALUE);
            finalSql = info.getDbmsType()
                .applySkipLimit(sql.toString(), values, sumSkip, minLimit);
            initialPipeline.removeIf(a -> filters.contains(a) || sorteds.contains(a) || skips.contains(a) || limits.contains(a));
        }

        query.setSql(finalSql);
        query.setValues(values);

        return initialPipeline;
    }

    private void traverse(Pipeline pipeline,
        final Consumer<? super FilterAction<ENTITY>> filterConsumer,
        final Consumer<? super SortedComparatorAction<ENTITY>> sortedConsumer,
        final Consumer<? super SkipAction<ENTITY>> skipConsumer,
        final Consumer<? super LimitAction<ENTITY>> limitConsumer
    ) {
        if (pipeline.isEmpty()) {
            return;
        }

        final Consumers<ENTITY> consumers = new Consumers<>(filterConsumer, sortedConsumer, skipConsumer, limitConsumer);

        final Action<?, ?> firstAction = pipeline.getFirst();
        final List<Operation<ENTITY>> path;
        if (isFilterActionAndContainingOnlyFieldPredicate(firstAction)) {
            path = FILTER_SORTED_SKIP_PATH;
        } else {
            path = SORTED_FILTER_SKIP_PATH;
        }

        Operation<ENTITY> operation = path.get(0);

        for (Action<?, ?> action : pipeline) {

            if (operation == path.get(0)) {
                if (operation.is(action)) {
                    operation.consume(action, consumers);
                } else {
                    if (path.get(1).is(action)) {
                        operation = path.get(1);
                    } else {
                        if (path.get(2).is(action)) {
                            operation = path.get(2);
                        } else {
                            if (path.get(3).is(action)) {
                                operation = path.get(3);
                            } else {
                                return;
                            }
                        }
                    }
                }
            }

            if (operation == path.get(1)) {
                if (operation.is(action)) {
                    operation.consume(action, consumers);
                } else {
                    if (path.get(2).is(action)) {
                        operation = path.get(2);
                    } else {
                        if (path.get(3).is(action)) {
                            operation = path.get(3);
                        } else {
                            return;
                        }
                    }
                }
            }

            if (operation == path.get(2)) {
                if (operation.is(action)) {
                    operation.consume(action, consumers);
                } else {
                    if (path.get(3).is(action)) {
                        operation = path.get(3);
                    } else {
                        return;
                    }
                }
            }

            if (operation == path.get(3)) {
                if (operation.is(action)) {
                    operation.consume(action, consumers);
                } else {
                    return;
                }
            }
        }
    }

    private boolean isFilterActionAndContainingOnlyFieldPredicate(Action<?, ?> action) {
        if (action instanceof FilterAction) {
            @SuppressWarnings("unchecked")
            final FilterAction<ENTITY> filterAction = (FilterAction<ENTITY>) action;
            return isContainingOnlyFieldPredicate(filterAction.getPredicate());
        }
        return false;
    }

    private static class Consumers<ENTITY> {

        private final Consumer<? super FilterAction<ENTITY>> filterConsumer;
        private final Consumer<? super SortedComparatorAction<ENTITY>> sortedConsumer;
        private final Consumer<? super SkipAction<ENTITY>> skipConsumer;
        private final Consumer<? super LimitAction<ENTITY>> limitConsumer;

        public Consumers(
            final Consumer<? super FilterAction<ENTITY>> filterConsumer,
            final Consumer<? super SortedComparatorAction<ENTITY>> sortedConsumer,
            final Consumer<? super SkipAction<ENTITY>> skipConsumer,
            final Consumer<? super LimitAction<ENTITY>> limitConsumer
        ) {
            this.filterConsumer = requireNonNull(filterConsumer);
            this.sortedConsumer = requireNonNull(sortedConsumer);;
            this.skipConsumer = requireNonNull(skipConsumer);
            this.limitConsumer = requireNonNull(limitConsumer);
        }

        public Consumer<? super FilterAction<ENTITY>> getFilterConsumer() {
            return filterConsumer;
        }

        public Consumer<? super SortedComparatorAction<ENTITY>> getSortedConsumer() {
            return sortedConsumer;
        }

        public Consumer<? super SkipAction<ENTITY>> getSkipConsumer() {
            return skipConsumer;
        }

        public Consumer<? super LimitAction<ENTITY>> getLimitConsumer() {
            return limitConsumer;
        }

    }

    private interface Operation<ENTITY> {

        boolean is(Action<?, ?> action);

        void consume(Action<?, ?> action, Consumers<ENTITY> consumers);

    }

    private class FilterOperation implements Operation<ENTITY> {

        @Override
        public boolean is(Action<?, ?> action) {
            return isFilterActionAndContainingOnlyFieldPredicate(action);
        }

        @Override
        public void consume(Action<?, ?> action, Consumers<ENTITY> consumers) {
            @SuppressWarnings("unchecked")
            final FilterAction<ENTITY> filterAction = (FilterAction<ENTITY>) action;
            consumers.getFilterConsumer().accept(filterAction);
        }

    }

    private class SortedOperation implements Operation<ENTITY> {

        @Override
        public boolean is(Action<?, ?> action) {
            return isSortedActionWithFieldPredicate(action);
        }

        @Override
        public void consume(Action<?, ?> action, Consumers<ENTITY> consumers) {
            @SuppressWarnings("unchecked")
            final SortedComparatorAction<ENTITY> sortedAction = (SortedComparatorAction<ENTITY>) action;
            consumers.getSortedConsumer().accept(sortedAction);
        }

    }

    private class SkipOperation implements Operation<ENTITY> {

        @Override
        public boolean is(Action<?, ?> action) {
            return action instanceof SkipAction;
        }

        @Override
        public void consume(Action<?, ?> action, Consumers<ENTITY> consumers) {
            @SuppressWarnings("unchecked")
            final SkipAction<ENTITY> skipAction = (SkipAction<ENTITY>) action;
            consumers.getSkipConsumer().accept(skipAction);
        }

    }

    private class LimitOperation implements Operation<ENTITY> {

        @Override
        public boolean is(Action<?, ?> action) {
            return action instanceof LimitAction;
        }

        @Override
        public void consume(Action<?, ?> action, Consumers<ENTITY> consumers) {
            @SuppressWarnings("unchecked")
            final LimitAction<ENTITY> limitAction = (LimitAction<ENTITY>) action;
            consumers.getLimitConsumer().accept(limitAction);
        }

    }

}
