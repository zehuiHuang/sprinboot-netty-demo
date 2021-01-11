package com.huiying.my.hashedWheelTimer;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class MyHashedWheelTimerTest {

    public static void main(String[] args) {
        Timer timer = new MyHashedWheelTimer();

        Timeout timeout1 = timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout1: " + new Date());
            }
        }, 10, TimeUnit.SECONDS);
    }

}
