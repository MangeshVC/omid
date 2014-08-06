package com.yahoo.omid.tsoclient;

import java.util.concurrent.Future;
import java.util.concurrent.Executor;

public interface TSOFuture<T> extends Future<T> {
    public void addListener(Runnable listener, Executor executor);
}