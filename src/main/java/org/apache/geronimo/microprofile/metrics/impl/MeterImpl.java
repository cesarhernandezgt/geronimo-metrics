/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.microprofile.metrics.impl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.json.bind.annotation.JsonbProperty;

import org.eclipse.microprofile.metrics.Meter;

public class MeterImpl implements Meter {
    private static final long INTERVAL_NS;
    private static final double ALPHA_MN;
    private static final double ALPHA_5MN;
    private static final double ALPHA_15MN;
    static {
        final double interval = 5; // this is not needed at runtime so no need of a constant
        INTERVAL_NS = TimeUnit.SECONDS.toNanos((int) interval * 60);
        ALPHA_MN = 1 - Math.exp(-interval / 60.);
        ALPHA_5MN = 1 - Math.exp(-interval / 12.);
        ALPHA_15MN = 1 - Math.exp(-interval / 4.);
    }

    private final LongAdder count = new LongAdder();
    private final Rate rate15 = new Rate(ALPHA_15MN, INTERVAL_NS);
    private final Rate rate5 = new Rate(ALPHA_5MN, INTERVAL_NS);
    private final Rate rate1 = new Rate(ALPHA_MN, INTERVAL_NS);
    private final long initNs = System.nanoTime();
    private final AtomicLong lastUpdate = new AtomicLong(System.nanoTime());
    private final String unit;

    public MeterImpl(final String unit) {
        this.unit = unit;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public void mark() {
        mark(1);
    }

    @Override // this is not the most beautiful piece but locking here would be a perf killer
    public void mark(final long n) {
        doRefresh();
        count.add(n);
        rate1.update(n);
        rate5.update(n);
        rate15.update(n);
    }

    @Override
    public long getCount() {
        return count.sum();
    }

    @Override
    @JsonbProperty("fifteenMinRate")
    public double getFifteenMinuteRate() {
        doRefresh();
        return rate15.value;
    }

    @Override
    @JsonbProperty("fiveMinRate")
    public double getFiveMinuteRate() {
        doRefresh();
        return rate5.value;
    }

    @Override
    @JsonbProperty("oneMinRate")
    public double getOneMinuteRate() {
        doRefresh();
        return rate1.value;
    }

    @Override
    public double getMeanRate() {
        final long count = getCount();
        if (count == 0) {
            return 0;
        }
        final long duration = System.nanoTime() - initNs;
        if (duration == 0) {
            return 0;
        }
        final long seconds = TimeUnit.NANOSECONDS.toSeconds(duration);
        if (seconds == 0) {
            return 0;
        }
        return count / seconds;
    }

    private void doRefresh() {
        final long now = System.nanoTime();
        final long lastUpdateNs = lastUpdate.get();
        final long elaspsedTime = now - lastUpdateNs;
        if (elaspsedTime > INTERVAL_NS && lastUpdate.compareAndSet(lastUpdateNs, now)) {
            final long diff = elaspsedTime / INTERVAL_NS;
            for (long it = 0; it < diff; it++) { // simulate time, avoids a background thread
                rate1.refresh();
                rate5.refresh();
                rate15.refresh();
            }
        }
    }

    private static class Rate {
        private volatile double value = 0;

        private final double alpha;
        private final double interval;
        private final LongAdder updates = new LongAdder();
        private volatile boolean initialized = false;

        private Rate(final double alpha, final long interval) {
            this.interval = interval;
            this.alpha = alpha;
        }

        private void update(final long n) {
            updates.add(n);
        }

        private void refresh() {
            final long count = updates.sumThenReset();
            final double val = count / interval;
            if (!initialized) {
                synchronized (this) {
                    value = val;
                }
                initialized = true;
                return;
            }
            synchronized (this) {
                value += (val - value) * alpha;
            }
        }
    }
}
