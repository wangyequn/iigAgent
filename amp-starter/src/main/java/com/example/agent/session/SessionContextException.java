package com.example.agent.session;

/** 会话上下文不合法（HTTP 400 SESSION_CONTEXT_INVALID）。 */
public class SessionContextException extends RuntimeException {

    public SessionContextException(String message) {
        super(message);
    }
}
