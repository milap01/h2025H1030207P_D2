import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class h2025H1030207P_D2Test {
    @TempDir Path tempDirectory;

    @Test
    void fixedLevelPolicyOrdersUpToReorderLevel() {
        h2025H1030207P_D2.Part part = new h2025H1030207P_D2.Part("P1", "Test", 1, false, 5, "consumable", 2, 8);
        assertEquals(3, new h2025H1030207P_D2.FixedLevelPolicy().quantity(2, part));
        assertEquals(0, new h2025H1030207P_D2.FixedLevelPolicy().quantity(6, part));
    }

    @Test
    void minMaxPolicyOnlyOrdersBelowMinimum() {
        h2025H1030207P_D2.Part part = new h2025H1030207P_D2.Part("P1", "Test", 1, false, 5, "consumable", 2, 8);
        h2025H1030207P_D2.MinMaxPolicy policy = new h2025H1030207P_D2.MinMaxPolicy();
        assertEquals(7, policy.quantity(1, part));
        assertEquals(0, policy.quantity(2, part));
        assertEquals(0, policy.quantity(3, part));
    }

    @Test
    void stockItemRejectsIllegalStateTransition() {
        h2025H1030207P_D2.StockItem item = new h2025H1030207P_D2.StockItem("P1", "S1");
        assertThrows(IllegalStateException.class, () -> item.transition(h2025H1030207P_D2.ItemStatus.INSTALLED));
        item.transition(h2025H1030207P_D2.ItemStatus.RESERVED);
        item.transition(h2025H1030207P_D2.ItemStatus.ISSUED);
        item.transition(h2025H1030207P_D2.ItemStatus.INSTALLED);
        assertEquals(h2025H1030207P_D2.ItemStatus.INSTALLED, item.status);
    }

    @Test
    void requisitionFollowsAllowedLifecycle() {
        h2025H1030207P_D2.Requisition request = new h2025H1030207P_D2.Requisition("REQ-0001", "tech", "AC-01",
            h2025H1030207P_D2.Priority.ROUTINE, new h2025H1030207P_D2.ReqLine("P1", 1));
        request.transition(h2025H1030207P_D2.ReqStatus.APPROVED);
        request.transition(h2025H1030207P_D2.ReqStatus.FULFILLED);
        assertEquals(h2025H1030207P_D2.ReqStatus.FULFILLED, request.status);
        assertThrows(IllegalStateException.class, () -> request.transition(h2025H1030207P_D2.ReqStatus.REJECTED));
    }

    @Test
    void technicianCannotReceiveStock() {
        h2025H1030207P_D2.InventoryRepository repository = new h2025H1030207P_D2.InventoryRepository(tempDirectory);
        h2025H1030207P_D2.InventoryService service = new h2025H1030207P_D2.InventoryService(repository);
        assertThrows(SecurityException.class,
            () -> service.receive("tech", h2025H1030207P_D2.Role.TECHNICIAN, "FLT-101", 1));
    }

    @Test
    void policiesProduceComparableSimulationMetrics() {
        h2025H1030207P_D2.PolicyMetrics fixed = h2025H1030207P_D2.simulate(new h2025H1030207P_D2.FixedLevelPolicy());
        h2025H1030207P_D2.PolicyMetrics minMax = h2025H1030207P_D2.simulate(new h2025H1030207P_D2.MinMaxPolicy());
        assertTrue(fixed.stockouts >= 0);
        assertTrue(minMax.stockouts >= 0);
        assertTrue(fixed.meanAogHours >= 0);
        assertTrue(minMax.meanAogHours >= 0);
        assertNotEquals(fixed.policy, minMax.policy);
        assertNotEquals(fixed.stockouts, minMax.stockouts);
    }
}
