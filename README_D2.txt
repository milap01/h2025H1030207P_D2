Military Aviation Spare Parts Inventory System - D2 demonstration notes

Programming language: Java 17. The console application uses only the Java
standard library. JUnit 5 is used for automated tests through Maven.

Requirements:
    JDK 17 or later (a JDK is needed because compilation uses javac).
    Maven is optional for running the application and is needed only for tests.
    Confirm the tools are available with: java -version and javac -version

Compile and run (from this project folder; works on macOS, Windows, and Linux
when the JDK bin directory is on PATH):
    javac --release 17 h2025H1030207P_D2.java
    java h2025H1030207P_D2

Run the automated tests with Maven:
    mvn test
Maven may need internet access on its first run to download JUnit and build plugins.

The program creates inventory-data/parts.csv and inventory-data/stock.csv on
first run and reloads the catalogue and item states from those files later. It
also appends transaction entries to inventory-data/audit.csv. Reports are saved
under reports/. Delete the inventory-data directory to restore the initial
fictional sample data.

JUnit tests are in tests/ and use Maven. If Maven is installed, run:
    mvn test

The project targets Java 17. A JDK 17 or later is required; the paths above use
the JDK 25 installation found on the development computer and compile for Java 17.

Menu option 19 compares the fixed-level and minimum-maximum policies against
the same 12-month synthetic demand sequence with a 30-day replenishment lead
time. It reports stock-out events and mean AOG duration for each policy.

Demonstration users (prototype role checks; no password authentication):
    keeper     store keeper: add/update parts, receive/issue stock, repair returns
    tech       technician: raise requisitions, declare AOG, fit parts
    planner    planner: approve non-critical requests, select policies, proposals
    commander  commander: approve critical-part requests, AOG and reports

Suggested demo:
1. First log in as planner and choose option 10 to set minmax. Log out. Then log in as tech. Raise an AOG request for ROT-220, quantity 1, tail AC-01;
   mark the aircraft grounded. The seeded catalogue has no ROT-220 stock. This
   records the AOG event and opens a reorder proposal.
2. Log in as planner. View proposals with option 11, then approve ROT-220 with option 12.
3. Log in as keeper and receive the approved ROT-220 proposal from option 17.
   Use option 1 to note the serial number of the received item.
4. Log in as commander and approve REQ-0001. Critical parts require commander
   approval and the available item is reserved.
5. Log in as keeper and issue REQ-0001.
6. Log in as tech, choose option 1 to find the issued serial, then fit it with
   option 6. The AOG event closes and its duration is recorded.
7. Run stock and AOG reports; both display in the console and save to reports/.
8. Choose option 19 to compare the two reorder policies using synthetic demand.

OOAD elements represented: InventoryService acts as Facade; InventoryRepository
handles CSV storage; FixedLevelPolicy and MinMaxPolicy use Strategy; state
transitions are guarded in StockItem and Requisition; receiving selects an item
serial format by part type; listeners provide Observer-style notifications.
Data is fictional and intended only for the course demonstration.

Scope note: this is a compact academic prototype. The catalogue, stock item states, requisitions, reorder proposals, AOG events,
and audit log persist in CSV files. Reorder policies are selectable while the
application is running. Authentication is a simple role selection, not a
password system. Policy comparison data is synthetic and illustrative, not an
evaluation of operational fleet data. This is not an operational logistics
system.
