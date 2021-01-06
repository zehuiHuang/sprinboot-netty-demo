package com.huiying.schedule;

import com.huiying.HttpClient;

import java.util.Timer;
import java.util.TimerTask;

public class ScheduleApplation {

    public static void test() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // do something
                System.out.println("11111111111111");
            }
        }, 10000, 1000);  // 10s 后调度一个周期为 1s 的定时任务
    }

    public static void main(String[] args) throws Exception {
       //test();
       int k=56;
        int j = k >> 1;
        System.out.println(j);
    }
}
