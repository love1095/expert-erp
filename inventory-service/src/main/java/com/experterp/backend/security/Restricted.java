package com.experterp.backend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A response field only users with this permission see (phase I1: cost, stock value, margins). For anyone else it is
 * sent as null, so the page shows nothing rather than a wrong zero.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface Restricted {
    Permission value();
}
