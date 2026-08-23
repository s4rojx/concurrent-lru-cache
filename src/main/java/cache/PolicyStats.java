package cache;

public record PolicyStats(
        String policyName, long admissions, long rejections, double rejectionRate) {

    public static PolicyStats empty() {
        return new PolicyStats("NONE", 0, 0, 0.0);
    }
}
