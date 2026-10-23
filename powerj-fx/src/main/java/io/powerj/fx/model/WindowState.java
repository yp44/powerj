package io.powerj.fx.model;

/**
 * Where the window was and how big, to put it back there. Sizes and positions are in pixels.
 */
public record WindowState(double x, double y, double width, double height) {

    /** At least this much of the window must stay on the screen, in each direction. */
    private static final double VISIBLE = 100;
    private static final double MIN_SIZE = 200;

    /** {@code true} if the numbers can be a window: finite, and a size of some size. */
    public boolean isUsable() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height)
                && width >= MIN_SIZE && height >= MIN_SIZE;
    }

    /**
     * The same window brought back on a screen that may be smaller or have changed since: no bigger than the screen, and with
     * part of it where it can be reached.
     *
     * @param screenX left of the screen, and so on: the area a window can use
     */
    public WindowState fit(double screenX, double screenY, double screenWidth, double screenHeight) {
        double w = Math.min(width, screenWidth);
        double h = Math.min(height, screenHeight);
        double left = Math.clamp(x, screenX - w + VISIBLE, screenX + screenWidth - VISIBLE);
        double top = Math.clamp(y, screenY, screenY + screenHeight - VISIBLE);
        return new WindowState(left, top, w, h);
    }
}
