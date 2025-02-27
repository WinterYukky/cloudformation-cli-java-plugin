/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation.proxy;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.cloudformation.proxy.delay.*;

public class DelayTest {

    @Test
    public void fixedDelay() {
        final Delay fixed = Constant.of().delay(Duration.ofMillis(50)).timeout(Duration.ofMillis(5 * 50)).build();
        int[] attempt = { 1 };
        final Executable fn = () -> {
            Duration next = Duration.ZERO;
            while ((next = fixed.nextDelay(attempt[0])) != Duration.ZERO) {
                attempt[0]++;
                TimeUnit.MILLISECONDS.sleep(next.toMillis());
            }
        };
        //
        // Small 5ms jitter to ensure we complete within time
        //
        Assertions.assertTimeout(Duration.ofMillis(6 * 50), fn);
        assertThat(6).isEqualTo(attempt[0]);
        assertThat(fixed.nextDelay(8) == Duration.ZERO).isEqualTo(true);
        assertThat(fixed.nextDelay(10) == Duration.ZERO).isEqualTo(true);
        assertThat(fixed.nextDelay(15) == Duration.ZERO).isEqualTo(true);
    }

    @Test
    public void fixedDelayIter() {
        final Delay fixed = Constant.of().delay(Duration.ofMillis(50)).timeout(Duration.ofMillis(5 * 50)).build();
        try {
            int attempt = 1;
            Duration next = Duration.ZERO;
            long accrued = 0L, jitter = 2L;
            Duration later = Duration.ZERO;
            while ((next = fixed.nextDelay(attempt)) != Duration.ZERO) {
                attempt++;
                accrued += next.toMillis();
                TimeUnit.MILLISECONDS.sleep(next.toMillis());
                later = later.plusMillis(50);
                long total = later.toMillis();
                boolean range = accrued >= (total - jitter) && accrued <= (total + jitter);
                assertThat(range).isEqualTo(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void fixedDelays() {
        final Delay fixed = Constant.of().delay(Duration.ofMillis(5)).timeout(Duration.ofMillis(50)).build();
        Duration next = Duration.ZERO;
        long accrued = 0L;
        int attempt = 1;
        while ((next = fixed.nextDelay(attempt++)) != Duration.ZERO) {
            Assertions.assertEquals(Duration.ofMillis(5), next);
            accrued += next.toMillis();
        }
        Assertions.assertEquals(5 * 10, accrued);
    }

    @Test
    public void shiftedMultipleOfDelay() {
        final Delay fixed = ShiftByMultipleOf.shiftedOf().delay(Duration.ofSeconds(5)).timeout(Duration.ofSeconds(105)).build();
        Duration next = Duration.ZERO;
        long accrued = 0L;
        int attempt = 1;
        while ((next = fixed.nextDelay(attempt)) != Duration.ZERO) {
            attempt++;
            accrued += next.getSeconds();
        }
        Assertions.assertEquals(5 + 15 + 35 + 65 + 105, accrued);
        Assertions.assertEquals(6, attempt);
    }

    @Test
    public void multipleOfDelay() {
        final Delay fixed = MultipleOf.multipleOf().delay(Duration.ofSeconds(5)).timeout(Duration.ofSeconds(105)).build();
        Duration next = Duration.ZERO;
        long accrued = 0L;
        int attempt = 1;
        while ((next = fixed.nextDelay(attempt)) != Duration.ZERO) {
            attempt++;
            accrued += next.getSeconds();
        }
        assertThat(5 + 10 + 15 + 20 + 25 + 30).isEqualTo(accrued);
        assertThat(6).isEqualTo(attempt);
    }

    @Test
    public void multipleOfDelayBy4() {
        final Delay fixed = MultipleOf.multipleOf().delay(Duration.ofSeconds(5)).multiple(4).timeout(Duration.ofSeconds(105))
            .build();
        Duration next = Duration.ZERO;
        long accrued = 0L;
        int attempt = 1;
        while ((next = fixed.nextDelay(attempt)) != Duration.ZERO) {
            attempt++;
            accrued += next.getSeconds();
        }
        assertThat(5 + 20 + 40).isEqualTo(accrued);
        assertThat(4).isEqualTo(attempt);
    }

    @Test
    public void blendedDelay() {
        final Delay delay = Blended.of().add(Constant.of().delay(Duration.ofSeconds(5)).timeout(Duration.ofSeconds(20)).build())
            .add(ShiftByMultipleOf.shiftedOf().delay(Duration.ofSeconds(5)).timeout(Duration.ofSeconds(220)).build()).build();
        Duration next = Duration.ZERO;
        long accrued = 0L;
        int attempt = 1;
        while ((next = delay.nextDelay(attempt)) != Duration.ZERO) {
            attempt++;
            accrued += next.getSeconds();
        }
        assertThat(5 * 4 + 40 + 90 + 150 + 220).isEqualTo(accrued);
        assertThat(9).isEqualTo(attempt);
    }

    @Test
    public void exponentialDelays() {
        final Delay exponential = Exponential.of().timeout(Duration.ofSeconds(Math.round(Math.pow(2, 9)))).build();
        int attempt = 1;
        Duration next = Duration.ZERO;
        long accrued = 0L;
        while ((next = exponential.nextDelay(attempt)) != Duration.ZERO) {
            assertThat(Math.round(Math.pow(2, attempt))).isEqualTo(next.getSeconds());
            attempt++;
            accrued += next.getSeconds();
        }
        assertThat(9).isEqualTo(attempt);

        final Delay delay = Exponential.of().minDelay(Duration.ofSeconds(10))
            .timeout(Duration.ofSeconds(Math.round(Math.pow(10, 9)))).powerBy(10).build();
        attempt = 1;
        accrued = 0L;
        while ((next = delay.nextDelay(attempt)) != Duration.ZERO) {
            assertThat(Math.round(Math.pow(10, attempt))).isEqualTo(next.getSeconds());
            attempt++;
            accrued += next.getSeconds();
        }
        assertThat(9).isEqualTo(attempt);

        final Delay expoPower = Exponential.of().minDelay(Duration.ofSeconds(4)).timeout(Duration.ofSeconds(64)).powerBy(4)
            .build();
        attempt = 1;
        accrued = 0L;
        while ((next = expoPower.nextDelay(attempt)) != Duration.ZERO) {
            switch ((int) next.getSeconds()) {
                case 4:
                case 16:
                case 64:
                    break;

                default:
                    Assertions.fail("power of 4 error " + next + " attempt " + attempt);
            }
            ++attempt;
            accrued += next.getSeconds();
        }
        assertThat(3).isEqualTo(attempt);

        final Delay expoPower2 = Exponential.of().minDelay(Duration.ofSeconds(16)).timeout(Duration.ofSeconds(64)).powerBy(4)
            .build();
        attempt = 1;
        accrued = 0L;
        while ((next = expoPower2.nextDelay(attempt)) != Duration.ZERO) {
            switch ((int) next.getSeconds()) {
                case 16:
                case 64:
                    break;

                default:
                    Assertions.fail("power of 4 error " + next + " attempt " + attempt);
            }
            ++attempt;
            accrued += next.getSeconds();
        }
        assertThat(3).isEqualTo(attempt);
    }

    @Test
    public void cappedExponentialDelays() {
        Duration MAX_DELAY = Duration.ofSeconds(15);
        final Delay cappedExponential = CappedExponential.of().timeout(Duration.ofMinutes(20)).maxDelay(MAX_DELAY).powerBy(1.3)
            .minDelay(Duration.ofSeconds(1)).build();
        int[] results = { 1, 1, 2, 2, 3, 4, 5, 6, 8, 11, 14, 15, 15, 15, 15, 15, 15 };
        for (int tries = 0; tries <= 15; tries++) {
            Duration delay = cappedExponential.nextDelay(tries);
            assertThat(results[tries]).isEqualTo((int) delay.getSeconds());
            if (tries >= 11) {
                assertThat(MAX_DELAY.getSeconds()).isEqualTo(delay.getSeconds());
            }
        }

        // If powerBy is not passed, it's set to default 2.
        final Delay cappedExponentialNoPower = CappedExponential.of().timeout(Duration.ofMinutes(20)).maxDelay(MAX_DELAY)
            .minDelay(Duration.ofSeconds(2)).build();
        int[] resultsNoPower = { 2, 2, 4, 8, 15, 15, 15, 15, 15 };
        for (int tries = 0; tries <= 6; tries++) {
            Duration delay = cappedExponentialNoPower.nextDelay(tries);
            assertThat(resultsNoPower[tries]).isEqualTo((int) delay.getSeconds());
            if (tries >= 5) {
                assertThat(MAX_DELAY.getSeconds()).isEqualTo(delay.getSeconds());
            }
        }

        // If timeout is reached the delay is 0
        final Delay cappedExponentialTimeout = CappedExponential.of().timeout(Duration.ofSeconds(5))
            .maxDelay(Duration.ofSeconds(1)).powerBy(1.0).minDelay(Duration.ofSeconds(1)).build();

        int[] resultsTimeout = { 1, 1, 1, 1, 1, 0 };
        for (int tries = 0; tries <= 5; tries++) {
            Duration delay = cappedExponentialTimeout.nextDelay(tries);
            assertThat(resultsTimeout[tries]).isEqualTo((int) delay.getSeconds());
            if (tries >= 5) {
                assertThat(0).isEqualTo(delay.getSeconds());
            }
        }

        // If minDelay is not passed, it's set to default 1.
        final Delay cappedExponentialNoMinDelay = CappedExponential.of().timeout(Duration.ofSeconds(5))
            .maxDelay(Duration.ofSeconds(1)).powerBy(1.0).build();
        int[] resultsNoMinDelay = { 1, 1, 1, 1, 1, 0 };
        for (int tries = 0; tries <= 5; tries++) {
            Duration delay = cappedExponentialNoMinDelay.nextDelay(tries);
            assertThat(resultsNoMinDelay[tries]).isEqualTo((int) delay.getSeconds());
        }

        // If maxDelay is not passed, it's set to default 20 sec.
        final Delay cappedExponentialNoMaxDelay = CappedExponential.of().timeout(Duration.ofMinutes(20))
            .minDelay(Duration.ofSeconds(2)).build();
        int[] resultsNoMaxDelay = { 2, 2, 4, 8, 16, 20, 20, 20, 20 };
        for (int tries = 0; tries <= 6; tries++) {
            Duration delay = cappedExponentialNoMaxDelay.nextDelay(tries);
            assertThat(resultsNoMaxDelay[tries]).isEqualTo((int) delay.getSeconds());
        }

        final Delay cappedExponentialSameMinMaxDelay = CappedExponential.of().timeout(Duration.ofSeconds(5))
            .maxDelay(Duration.ofSeconds(1)).powerBy(1.3).minDelay(Duration.ofSeconds(1)).build();
        int[] resultsSameMinMaxDelay = { 1, 1, 1, 1, 1, 0 };
        for (int tries = 0; tries <= 5; tries++) {
            Duration delay = cappedExponentialSameMinMaxDelay.nextDelay(tries);
            assertThat(resultsSameMinMaxDelay[tries]).isEqualTo((int) delay.getSeconds());
            if (tries >= 5) {
                assertThat(0).isEqualTo(delay.getSeconds());
            }
        }
    }
}
