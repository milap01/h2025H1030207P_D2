import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventoryAppTest {
    @TempDir Path tempDirectory;

    @Test
    void fixedLevelPolicyOrdersUpToReorderLevel() {
        InventoryApp.Part part = new InventoryApp.Part("P1", "Test", 1, false, 5, "consumable", 2, 8);
        assertEquals(3, new InventoryApp.FixedLevelPolicy().quantity(2, part));
        assertEquals(0, new InventoryApp.FixedLevelPolicy().quantity(6, part));
    }

    @Test
    void minMaxPolicyOnlyOrdersBelowMinimum() {
        InventoryApp.Part part = new InventoryApp.Part("P1", "Test", 1, false, 5, "consumable", 2, 8);
        InventoryApp.MinMaxPolicy policy = new InventoryApp.MinMaxPolicy();
        assertEquals(7, policy.quantity(1, part));
        assertEquals(0, policy.quantity(2, part));
        assertEquals(0, policy.quantity(3, part));
    }

    @Test
    void stockItemRejectsIllegalStateTransition() {
        InventoryApp.StockItem item = new InventoryApp.StockItem("P1", "S1");
        assertThrows(IllegalStateException.class, () -> item.transition(InventoryApp.ItemStatus.INSTALLED));
        item.transition(InventoryApp.ItemStatus.RESERVED);
        item.transition(InventoryApp.ItemStatus.ISSUED);
        item.transition(InventoryApp.ItemStatus.INSTALLED);
        assertEquals(InventoryApp.ItemStatus.INSTALLED, item.status);
    }

    @Test
    void requisitionFollowsAllowedLifecycle() {
        InventoryApp.Requisition request = new InventoryApp.Requisition("REQ-0001", "tech", "AC-01",
            InventoryApp.Priority.ROUTINE, new InventoryApp.ReqLine("P1", 1));
        request.transition(InventoryApp.ReqStatus.APPROVED);
        request.transition(InventoryApp.ReqStatus.FULFILLED);
        assertEquals(InventoryApp.ReqStatus.FULFILLED, request.status);
        assertThrows(IllegalStateException.class, () -> request.transition(InventoryApp.ReqStatus.REJECTED));
    }

    @Test
    void technicianCannotReceiveStock() {
        InventoryApp.InventoryRepository repository = new InventoryApp.InventoryRepository(tempDirectory);
        InventoryApp.InventoryService service = new InventoryApp.InventoryService(repository);
        assertThrows(SecurityException.class,
            () -> service.receive("tech", InventoryApp.Role.TECHNICIAN, "FLT-101", 1));
    }

    @Test
    void policiesProduceComparableSimulationMetrics() {
        InventoryApp.PolicyMetrics fixed = InventoryApp.simulate(new InventoryApp.FixedLevelPolicy());
        InventoryApp.PolicyMetrics minMax = InventoryApp.simulate(new InventoryApp.MinMaxPolicy());
        assertTrue(fixed.stockouts >= 0);
        assertTrue(minMax.stockouts >= 0);
        assertTrue(fixed.meanAogHours >= 0);
        assertTrue(minMax.meanAogHours >= 0);
        assertNotEquals(fixed.policy, minMax.policy);
        assertNotEquals(fixed.stockouts, minMax.stockouts);
    }
}
