package com.speedment.common.codegen.internal.model.value;

import com.speedment.common.codegen.internal.util.Copier;
import com.speedment.common.codegen.model.Value;
import com.speedment.common.codegen.model.trait.HasCopy;
import com.speedment.common.codegen.model.value.InvocationValue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The default implementation of the {@link InvocationValue} interface.
 *
 * @author Emil Forslund
 * @since  2.4.6
 */
public final class InvocationValueImpl implements InvocationValue {

    private final List<Value<?>> args;

    private Type type;
    private String value;

    public InvocationValueImpl() {
        args = new ArrayList<>();
    }

    private InvocationValueImpl(InvocationValue prototype) {
        args  = Copier.copy(prototype.getValues(), HasCopy::copy);
        type  = prototype.getType();
        value = prototype.getValue();
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public InvocationValue setValue(String value) {
        this.value = value;
        return this;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public InvocationValue set(Type type) {
        this.type = type;
        return this;
    }

    @Override
    public List<Value<?>> getValues() {
        return args;
    }

    @Override
    public Value<String> copy() {
        return new InvocationValueImpl(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InvocationValue)) return false;

        final InvocationValue that = (InvocationValue) o;
        return args.equals(that.getValues())
            && (getType() != null
            ? getType().equals(that.getType())
            : that.getType() == null
        ) && (getValue() != null
            ? getValue().equals(that.getValue())
            : that.getValue() == null);
    }

    @Override
    public int hashCode() {
        int result = args.hashCode();
        result = 31 * result + (getType() != null ? getType().hashCode() : 0);
        result = 31 * result + (getValue() != null ? getValue().hashCode() : 0);
        return result;
    }
}
