package io.github.zzz8688.diagagent.tools.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PlatformTool {

    String description();

    String logicalName() default "";

    String strategyType() default "local-only";

    String preferredBackend() default "local";

    String fallbackBackend() default "";

    String strategyNotes() default "";

    boolean enabled() default true;

    boolean userVisible() default true;

    String transport() default "in-process";

    String frameworkBindingMode() default "annotation-binding";
}
