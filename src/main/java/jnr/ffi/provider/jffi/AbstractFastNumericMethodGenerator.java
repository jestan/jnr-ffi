package jnr.ffi.provider.jffi;

import com.kenai.jffi.CallContext;
import com.kenai.jffi.Function;
import com.kenai.jffi.ObjectParameterInfo;
import com.kenai.jffi.ObjectParameterStrategy;
import jnr.ffi.Pointer;
import jnr.ffi.Struct;
import jnr.ffi.NativeType;
import jnr.ffi.provider.ParameterFlags;

import org.objectweb.asm.Label;

import java.lang.annotation.Annotation;
import java.nio.*;
import java.util.*;

import static jnr.ffi.provider.jffi.AsmUtil.*;
import static jnr.ffi.provider.jffi.CodegenUtils.ci;
import static jnr.ffi.provider.jffi.CodegenUtils.p;
import static jnr.ffi.provider.jffi.NumberUtil.narrow;
import static jnr.ffi.provider.jffi.NumberUtil.sizeof;
import static jnr.ffi.provider.jffi.NumberUtil.widen;

/**
 *
 */
abstract class AbstractFastNumericMethodGenerator extends BaseMethodGenerator {
    private final BufferMethodGenerator bufgen;

    public AbstractFastNumericMethodGenerator(BufferMethodGenerator bufgen) {
        this.bufgen = bufgen;
    }

    private void emitNumericParameter(SkinnyMethodAdapter mv, final Class javaType, NativeType nativeType) {
        final Class nativeIntType = getInvokerType();

        if (Float.class == javaType || float.class == javaType) {
            if (!javaType.isPrimitive()) {
                unboxNumber(mv, javaType, float.class);
            }
            mv.invokestatic(Float.class, "floatToRawIntBits", int.class, float.class);
            widen(mv, int.class, nativeIntType);

        } else if (Double.class == javaType || double.class == javaType) {
            if (!javaType.isPrimitive()) {
                unboxNumber(mv, javaType, double.class);
            }
            mv.invokestatic(Double.class, "doubleToRawLongBits", long.class, double.class);

        } else if (javaType.isPrimitive()) {
            NumberUtil.convertPrimitive(mv, javaType, nativeIntType, nativeType);

        } else if (Number.class.isAssignableFrom(javaType)) {
            unboxNumber(mv, javaType, nativeIntType, nativeType);

        } else if (Boolean.class.isAssignableFrom(javaType)) {
            unboxBoolean(mv, javaType, nativeIntType);

        } else {
            throw new IllegalArgumentException("unsupported numeric type " + javaType);
        }
    }

    public void generate(AsmBuilder builder, SkinnyMethodAdapter mv, Function function, ResultType resultType, ParameterType[] parameterTypes,
                         boolean ignoreError) {
// [ stack contains: Invoker, Function ]
        AsmLocalVariableAllocator localVariableAllocator = new AsmLocalVariableAllocator(parameterTypes);

        final Class nativeIntType = getInvokerType();
        final AsmLocalVariable objCount = localVariableAllocator.allocate(int.class);
        AsmLocalVariable[] pointers = new AsmLocalVariable[parameterTypes.length];
        AsmLocalVariable[] strategies = new AsmLocalVariable[parameterTypes.length];
        int pointerCount = 0;

        // Load, convert, and un-box parameters
        for (int i = 0, lvar = 1; i < parameterTypes.length; ++i) {
            int nextParameterIndex = loadAndConvertParameter(builder, mv, lvar, parameterTypes[i]);

            Class javaParameterType = parameterTypes[i].effectiveJavaType();

            if (Pointer.class.isAssignableFrom(javaParameterType) && isDelegate(parameterTypes[i].javaType)) {
                // delegates are always direct, so handle without the strategy processing
                unboxPointer(mv, nativeIntType);

            } else if (Pointer.class.isAssignableFrom(javaParameterType)
                    || Struct.class.isAssignableFrom(javaParameterType)
                    || String.class == javaParameterType
                    || CharSequence.class == javaParameterType
                    || ByteBuffer.class.isAssignableFrom(javaParameterType)
                    || ShortBuffer.class.isAssignableFrom(javaParameterType)
                    || IntBuffer.class.isAssignableFrom(javaParameterType)
                    || (LongBuffer.class.isAssignableFrom(javaParameterType) && sizeof(NativeType.SLONG) == 8)
                    || FloatBuffer.class.isAssignableFrom(javaParameterType)
                    || DoubleBuffer.class.isAssignableFrom(javaParameterType)
                    || byte[].class == javaParameterType
                    || short[].class == javaParameterType
                    || int[].class == javaParameterType
                    || (long[].class == javaParameterType && sizeof(NativeType.SLONG) == 8)
                    || float[].class == javaParameterType
                    || double[].class == javaParameterType
                    ) {

                // Initialize the objectCount local var
                if (pointerCount++ < 1) {
                    mv.pushInt(0);
                    mv.istore(objCount);
                }

                strategies[i] = localVariableAllocator.allocate(ObjectParameterStrategy.class);

                if (parameterTypes[i].toNativeConverter != null) {
                    // Save the current pointer parameter (result of type conversion above)
                    pointers[i] = localVariableAllocator.allocate(Object.class);
                    mv.astore(pointers[i]);
                    mv.aload(pointers[i]);
                } else {
                    // avoid the save/load of an extra local var if no parameter conversion took place
                    pointers[i] = new AsmLocalVariable(lvar);
                }

                emitPointerParameterStrategyLookup(mv, javaParameterType, parameterTypes[i].annotations);

                mv.astore(strategies[i]);
                mv.aload(strategies[i]);

                mv.getfield(p(PointerParameterStrategy.class), "objectCount", ci(int.class));
                mv.iload(objCount);
                mv.iadd();
                mv.istore(objCount);


                if (CharSequence.class.isAssignableFrom(javaParameterType)
                    || javaParameterType.isArray() && javaParameterType.getComponentType().isPrimitive()) {
                    // heap objects are always address == 0, no need to call getAddress()
                    if (int.class == nativeIntType) mv.iconst_0(); else mv.lconst_0();
                } else {
                    // Get the native address (will return zero for heap objects)
                    mv.aload(strategies[i]);
                    mv.aload(pointers[i]);
                    mv.invokevirtual(PointerParameterStrategy.class, "address", long.class, Object.class);
                    narrow(mv, long.class, nativeIntType);
                }

            } else {
                emitNumericParameter(mv, javaParameterType, parameterTypes[i].nativeType);
            }
            lvar = nextParameterIndex;
        }

        // stack now contains [ IntInvoker, Function, int/long args ]
        Label hasObjects = new Label();
        Label convertResult = new Label();
        if (pointerCount > 0) {
            mv.iload(objCount);
            mv.ifne(hasObjects);
        }
        mv.invokevirtual(p(com.kenai.jffi.Invoker.class),
                getInvokerMethodName(resultType, parameterTypes, ignoreError),
                getInvokerSignature(parameterTypes.length, nativeIntType));

        if (pointerCount > 0) mv.label(convertResult);

        Class javaReturnType = resultType.effectiveJavaType();
        Class nativeReturnType = nativeIntType;

        // Convert the result from long/int to the correct return type
        if (Float.class == javaReturnType || float.class == javaReturnType) {
            narrow(mv, nativeIntType, int.class);
            mv.invokestatic(Float.class, "intBitsToFloat", float.class, int.class);
            nativeReturnType = float.class;

        } else if (Double.class == javaReturnType || double.class == javaReturnType) {
            widen(mv, nativeIntType, long.class);
            mv.invokestatic(Double.class, "longBitsToDouble", double.class, long.class);
            nativeReturnType = double.class;

        }

        // box and/or narrow/widen the return value if needed
        convertAndReturnResult(builder, mv, resultType, nativeReturnType);

        /* --  method returns above - below is an alternative path -- */

        // Now implement heap object support
        if (pointerCount > 0) {
            mv.label(hasObjects);
            if (int.class == nativeIntType) {
                // For int invoker, need to convert all the int args to long
                AsmLocalVariable[] tmp = new AsmLocalVariable[parameterTypes.length];
                for (int i = parameterTypes.length - 1; i > 0; i--) {
                    tmp[i] = localVariableAllocator.allocate(int.class);
                    mv.istore(tmp[i]);
                }
                if (parameterTypes.length > 0) mv.i2l();
                // Now reload them back onto the parameter stack, and widen to long
                for (int i = 1; i < parameterTypes.length; i++) {
                    mv.iload(tmp[i]);
                    mv.i2l();
                }
            }

            mv.iload(objCount);
            // Need to load all the converters onto the stack
            for (int i = 0; i < parameterTypes.length; i++) {
                if (pointers[i] != null) {
                    mv.aload(pointers[i]);
                    mv.aload(strategies[i]);
                    mv.aload(0);

                    ObjectParameterInfo info = ObjectParameterInfo.create(i,
                            AsmUtil.getNativeArrayFlags(parameterTypes[i].annotations));

                    mv.getfield(builder.getClassNamePath(), builder.getObjectParameterInfoName(info),
                            ci(ObjectParameterInfo.class));
                }
            }
            mv.invokevirtual(p(com.kenai.jffi.Invoker.class),
                    AbstractFastNumericMethodGenerator.getObjectParameterMethodName(parameterTypes.length),
                    AbstractFastNumericMethodGenerator.getObjectParameterMethodSignature(parameterTypes.length, pointerCount));
            if (int.class == nativeIntType) mv.l2i();

            mv.go_to(convertResult);
        }
    }

    @SuppressWarnings("unchecked")
    static final Set<Class> pointerTypes = Collections.unmodifiableSet(new LinkedHashSet<Class>(Arrays.asList(
        Pointer.class,
        CharSequence.class,
        ByteBuffer.class, ShortBuffer.class, IntBuffer.class, LongBuffer.class, FloatBuffer.class, DoubleBuffer.class,
        byte[].class, short[].class, char[].class, int[].class, long[].class, float[].class, double[].class, boolean[].class
    )));

    static void emitPointerParameterStrategyLookup(SkinnyMethodAdapter mv, Class javaParameterType, Annotation[] annotations) {
        boolean converted = false;
        for (Class c : pointerTypes) {
            if (c.isAssignableFrom(javaParameterType)) {
                mv.invokestatic(AsmRuntime.class, "pointerParameterStrategy", PointerParameterStrategy.class, c);
                converted = true;
            }
        }
        if (converted) {
            return;
        }

        if (Struct.class.isAssignableFrom(javaParameterType)) {
            if (ParameterFlags.isDirect(AsmUtil.getParameterFlags(annotations))) {
                // Force the struct to be passed using direct backing memory
                mv.invokestatic(AsmRuntime.class, "directStructParameterStrategy", PointerParameterStrategy.class, Struct.class);

            } else {
                mv.invokestatic(AsmRuntime.class, "structParameterStrategy", PointerParameterStrategy.class, Struct.class);
            }

        } else {
            throw new RuntimeException("no strategy for " + javaParameterType);
        }
    }

    static String getObjectParameterMethodName(int parameterCount) {
        return "invokeN" + parameterCount;
    }

    static String getObjectParameterMethodSignature(int parameterCount, int pointerCount) {
        StringBuilder sb = new StringBuilder();
        sb.append('(').append(ci(CallContext.class)).append(ci(long.class));
        for (int i = 0; i < parameterCount; i++) {
            sb.append('J');
        }
        sb.append('I'); // objCount
        for (int n = 0; n < pointerCount; n++) {
            sb.append(ci(Object.class));
            sb.append(ci(ObjectParameterStrategy.class));
            sb.append(ci(ObjectParameterInfo.class));
        }
        sb.append(")J");
        return sb.toString();
    }

    abstract String getInvokerMethodName(ResultType resultType, ParameterType[] parameterTypes,
                                         boolean ignoreErrno);

    abstract String getInvokerSignature(int parameterCount, Class nativeIntType);
    abstract Class getInvokerType();

    static boolean getBooleanProperty(String propertyName, boolean defaultValue) {
        return Boolean.valueOf(System.getProperty(propertyName, Boolean.valueOf(defaultValue).toString()));
    }
}
