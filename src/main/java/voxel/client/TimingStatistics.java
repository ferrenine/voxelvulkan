package voxel.client;

import java.util.ArrayList;
import java.util.List;

//Per-frame stopwatch registry. Each sampler accumulates nanoseconds between
// start()/stop() pairs during a frame; update() folds that elapsed time into
// a floor-clamped exponential moving average and clears the accumulator.
public class TimingStatistics {
    public static double ROLLING_WEIGHT = 0.96;

    private static final List<TimeSampler> REGISTRY = new ArrayList<>();

    public static final class TimeSampler {
        private boolean open;
        private long openNanos;
        private long elapsedNanos;
        private double average;

        public TimeSampler() {
            REGISTRY.add(this);
        }

        public void start() {
            if (this.open) {
                throw new IllegalStateException("Sampler already running");
            }
            this.openNanos = System.nanoTime();
            this.open = true;
        }

        public void stop() {
            if (!this.open) {
                throw new IllegalStateException("Sampler not running");
            }
            this.open = false;
            this.elapsedNanos += System.nanoTime() - this.openNanos;
        }

        //Compensate for time counted inside a nested sampler.
        public void subtract(TimeSampler sampler) {
            this.elapsedNanos -= sampler.elapsedNanos;
        }

        private void commitFrame() {
            if (this.open) {
                throw new IllegalStateException("Sampler still running at frame end");
            }
            double millis = this.elapsedNanos / 1_000_000.0;
            //Clamped EMA: the average never reports below the latest sample,
            // so a spike shows immediately instead of decaying in.
            this.average = Math.max(this.average * ROLLING_WEIGHT + millis * (1.0 - ROLLING_WEIGHT), millis);
            this.elapsedNanos = 0;
        }

        public double getRolling() {
            return this.average;
        }

        public String pVal() {
            return String.format("%6.3f", this.average);
        }
    }

    public static final TimeSampler all = new TimeSampler();
    public static final TimeSampler main = new TimeSampler();
    public static final TimeSampler dynamic = new TimeSampler();
    public static final TimeSampler postDynamic = new TimeSampler();

    public static final TimeSampler A = new TimeSampler();
    public static final TimeSampler B = new TimeSampler();
    public static final TimeSampler C = new TimeSampler();
    public static final TimeSampler D = new TimeSampler();

    public static final TimeSampler E = new TimeSampler();
    public static final TimeSampler F = new TimeSampler();
    public static final TimeSampler G = new TimeSampler();
    public static final TimeSampler H = new TimeSampler();
    public static final TimeSampler I = new TimeSampler();

    //Discard whatever accumulated since the last update() — called at frame
    // start so samplers skipped by early-outs cannot leak stale time forward.
    public static void resetSamplers() {
        for (TimeSampler sampler : REGISTRY) {
            sampler.elapsedNanos = 0;
        }
    }

    public static void update() {
        for (TimeSampler sampler : REGISTRY) {
            sampler.commitFrame();
        }
    }
}
