package com.scenttracker.models;

/**
 * Rosa dos ventos de 8 pontos. No Minecraft: Norte = -Z, Leste = +X.
 */
public enum CompassDirection {
    NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST;

    public static CompassDirection fromVector(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = norte, 90 = leste
        if (angle < 0) {
            angle += 360.0;
        }
        int index = (int) Math.round(angle / 45.0) % 8;
        return values()[index];
    }
}
