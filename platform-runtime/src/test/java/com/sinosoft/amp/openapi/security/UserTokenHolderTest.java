package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class UserTokenHolderTest {

    @AfterEach
    void tearDown() {
        UserTokenHolder.clearAll();
        AuthContext.clear();
    }

    @Test
    void alwaysNullWithoutSso() {
        UserTokenHolder.bind("alice", "tok-a");
        assertNull(UserTokenHolder.resolve());
        assertNull(UserTokenHolder.resolveOrSingleActive());
    }
}
