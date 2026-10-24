package io.powerj.fx.model;

/**
 * Values between two bounds, either of which may be missing (open); a bound is in or out of the interval.
 *
 * @param low  smallest value, {@code null} for none
 * @param high largest value, {@code null} for none
 */
record Interval<T extends Comparable<? super T>>(T low, boolean lowIncluded, T high, boolean highIncluded) {

    boolean contains(T value) {
        if (low != null) {
            int order = value.compareTo(low);
            if (order < 0 || order == 0 && !lowIncluded) {
                return false;
            }
        }
        if (high != null) {
            int order = value.compareTo(high);
            return order < 0 || order == 0 && highIncluded;
        }
        return true;
    }
}
