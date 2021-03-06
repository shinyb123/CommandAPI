package com.shinybunny.cmdapi.annotations;

import com.shinybunny.cmdapi.CommandAPI;
import com.shinybunny.cmdapi.CommandContext;
import com.shinybunny.cmdapi.arguments.ParameterArgument;
import com.shinybunny.cmdapi.exceptions.IncompatibleAnnotationException;
import com.shinybunny.cmdapi.exceptions.InvalidArgumentException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Default {

    String value() default "";

    double number() default 0;

    boolean bool() default false;

    class Adapter implements AnnotationAdapter<Default> {

        @Override
        public Class<Default> getAnnotationType() {
            return Default.class;
        }

        @Override
        public Object process(Object value, Default annotation, ParameterArgument arg, CommandContext ctx) throws InvalidArgumentException {
            if (value == null) {
                Object o = useDefault(annotation,arg.getType());
                System.out.println("used default: " + o);
                return o;
            }
            return value;
        }

        public static Object useDefault(Default annotation, Class<?> type) {
            System.out.println("class type : " + type);
            if (type == String.class) {
                return annotation.value();
            } else if (Number.class.isAssignableFrom(type)) {
                if (Integer.class == type) {
                    System.out.println("int");
                    return (int)annotation.number();
                } else if (Float.class == type) {
                    return (float)annotation.number();
                } else if (Short.class == type) {
                    return (short)((int)annotation.number());
                } else if (Long.class == type) {
                    return (long)annotation.number();
                } else {
                    return Byte.class == type ? (byte)annotation.number() : annotation.number();
                }
            } else if (type == Boolean.class) {
                return annotation.bool();
            } else {
                if (type.isEnum()) {
                    Object[] enums = type.getEnumConstants();

                    for (Object o : enums) {
                        if (o.toString().equalsIgnoreCase(annotation.value())) {
                            return o;
                        }
                    }
                }

                return null;
            }
        }

        @Override
        public void init(ParameterArgument argument, Default aDefault) throws IncompatibleAnnotationException {
            System.out.println("applied @Default to " + argument);
            System.out.println("default = " + aDefault.value() + "/" + aDefault.number() + "/" + aDefault.bool());
        }
    }

}
