/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

/**
 * Cylinder players stand in to capture: horizontal distance at most {@code radius} from the
 * centre, and vertical distance at most {@code radius}.
 */
public record CaptureZone(double x, double y, double z, double radius) {

    public CaptureZone {
        if (!(radius >= 1 && radius <= 64)) {
            throw new IllegalArgumentException("Capture radius must be between 1 and 64");
        }
    }

    public boolean contains(double px, double py, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(py - y) <= radius;
    }
}
