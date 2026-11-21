package com.vapk.app;

public interface BotApi {
    void move(String direction);
    void attack();
    void waitMs(int milliseconds) throws InterruptedException;
    void log(String message);
}
