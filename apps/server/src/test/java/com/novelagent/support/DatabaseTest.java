package com.novelagent.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against the local PostgreSQL only when {@code NOVEL_DB_TEST=true}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnabledIfEnvironmentVariable(named = "NOVEL_DB_TEST", matches = "true")
public @interface DatabaseTest {
}
