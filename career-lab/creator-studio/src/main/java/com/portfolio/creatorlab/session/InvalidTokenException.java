package com.portfolio.creatorlab.session;

/** [NEW-DESIGN] refresh token이 존재하지 않거나(이미 사용됨/알 수 없음) device가 일치하지 않을 때. */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
