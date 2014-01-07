/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package org.eigenbase.util;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.hydromatic.linq4j.function.Function1;

/**
 * Helps to run benchmarks by running the same task repeatedly and averaging
 * the running times.
 */
public class Benchmark {
  /**
   * Certain tests are enabled only if logging is enabled at debug level or
   * higher.
   */
  public static final Logger LOGGER =
      Logger.getLogger(Benchmark.class.getCanonicalName());

  private final Function1<Statistician, Void> function;
  private final int repeat;
  private final Statistician statistician;

  public Benchmark(String description, Function1<Statistician, Void> function,
      int repeat) {
    this.function = function;
    this.repeat = repeat;
    this.statistician = new Statistician(description);
  }

  /**
   * Returns whether performance tests are enabled.
   */
  public static boolean enabled() {
    return LOGGER.isLoggable(Level.FINE);
  }

  static long printDuration(String desc, long t0) {
    final long t1 = System.nanoTime();
    final long duration = t1 - t0;
    LOGGER.info(desc + " took " + duration + " nanos");
    return duration;
  }

  public void run() {
    for (int i = 0; i < repeat; i++) {
      function.apply(statistician);
    }
    statistician.printDurations();
  }

  /**
   * Collects statistics for a test that is run multiple times.
   */
  public static class Statistician {
    private final String desc;
    private final List<Long> durations = new ArrayList<Long>();

    public Statistician(String desc) {
      super();
      this.desc = desc;
    }

    public void record(long start) {
      durations.add(
          printDuration(
              desc + " iteration #" + (durations.size() + 1), start));
    }

    private void printDurations() {
      if (!LOGGER.isLoggable(Level.FINE)) {
        return;
      }

      List<Long> coreDurations = durations;
      String durationsString = durations.toString(); // save before sort

      // Ignore the first 3 readings. (JIT compilation takes a while to
      // kick in.)
      if (coreDurations.size() > 3) {
        coreDurations = durations.subList(3, durations.size());
      }
      Collections.sort(coreDurations);
      // Further ignore the max and min.
      List<Long> coreCoreDurations = coreDurations;
      if (coreDurations.size() > 4) {
        coreCoreDurations =
            coreDurations.subList(1, coreDurations.size() - 1);
      }
      long sum = 0;
      int count = coreCoreDurations.size();
      for (long duration : coreCoreDurations) {
        sum += duration;
      }
      final double avg = ((double) sum) / count;
      double y = 0;
      for (long duration : coreCoreDurations) {
        double x = duration - avg;
        y += x * x;
      }
      final double stddev = Math.sqrt(y / count);
      LOGGER.fine(
          desc + ": " + (durations.size() == 0
              ? "no runs"
              : durations.get(0)
                  + " first; "
                  + avg
                  + " +- "
                  + stddev
                  + "; "
                  + coreDurations.get(0)
                  + " min; "
                  + coreDurations.get(coreDurations.size() - 1)
                  + " max; "
                  + durationsString
                  + " nanos"));
    }
  }
}

// End Benchmark.java
