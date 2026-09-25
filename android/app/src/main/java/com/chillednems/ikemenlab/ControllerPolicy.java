package com.chillednems.ikemenlab;

/** Dead-zone and dominant-axis policy shared by joystick navigation and tests. */
public final class ControllerPolicy {
    public static final int NONE = 0, LEFT = 1, RIGHT = 2, UP = 3, DOWN = 4;
    private ControllerPolicy() {}

    public static int stickDirection(float x, float y) {
        if (Math.abs(x) > Math.abs(y)) return x > .6f ? RIGHT : x < -.6f ? LEFT : NONE;
        return y > .6f ? DOWN : y < -.6f ? UP : NONE;
    }
}
