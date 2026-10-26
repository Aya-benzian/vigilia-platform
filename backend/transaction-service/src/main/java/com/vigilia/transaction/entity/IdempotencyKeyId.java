package com.vigilia.transaction.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for IdempotencyKeyEntity consisting of scope and key.
 */
public class IdempotencyKeyId implements Serializable {

    private String scope;
    private String key;

    public IdempotencyKeyId() {
    }

    public IdempotencyKeyId(String scope, String key) {
        this.scope = scope;
        this.key = key;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyKeyId that = (IdempotencyKeyId) o;
        return Objects.equals(scope, that.scope) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, key);
    }
}
