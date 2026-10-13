package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserTokenHolderTest {

    @AfterEach
    void tearDown() {
        UserTokenHolder.clearAll();
        AuthContext.clear();
    }

    @Test
    void threadTokenWins() {
        UserTokenHolder.bind("alice", "tok-a");
        assertEquals("tok-a", UserTokenHolder.resolve());
    }

    @Test
    void fallbackToSingleActive() {
        UserTokenHolder.bind("alice", "tok-a");
        UserTokenHolder.clearThread();
        assertNull(UserTokenHolder.resolve());
        assertEquals("tok-a", UserTokenHolder.resolveOrSingleActive());
    }

    @Test
    void multiUserNoAmbiguousFallback() {
        UserTokenHolder.bind("alice", "tok-a");
        UserTokenHolder.bind("bob", "tok-b");
        UserTokenHolder.clearThread();
        assertNull(UserTokenHolder.resolveOrSingleActive());
    }
}
