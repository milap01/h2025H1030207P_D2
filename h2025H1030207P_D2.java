import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class h2025H1030207P_D2 {
    private static final Scanner IN = new Scanner(System.in);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Path DATA = Path.of("inventory-data");
    private static final Path REPORTS = Path.of("reports");
    private static InventoryService service;

    enum Role { STORE_KEEPER, TECHNICIAN, PLANNER, COMMANDER }
    enum Priority { ROUTINE, URGENT, AOG }
    enum ReqStatus { RAISED, APPROVED, WAITING_STOCK, REJECTED, FULFILLED }
    enum ItemStatus { IN_STOCK, RESERVED, ISSUED, INSTALLED, UNSERVICEABLE }

    static final class Part {
        final String number, description, kind;
        final double unitCost;
        final boolean critical;
        final int reorderLevel, minimum, maximum;
        Part(String number, String description, double unitCost, boolean critical,
             int reorderLevel, String kind, int minimum, int maximum) {
            this.number = number; this.description = description; this.unitCost = unitCost;
            this.critical = critical; this.reorderLevel = reorderLevel;
            this.kind = kind; this.minimum = minimum; this.maximum = maximum;
        }
    }

    static final class StockItem {
        final String partNumber, serial;
        ItemStatus status = ItemStatus.IN_STOCK;
        StockItem(String partNumber, String serial) { this.partNumber = partNumber; this.serial = serial; }
        void transition(ItemStatus target) {
            Map<ItemStatus, Set<ItemStatus>> allowed = Map.of(
                ItemStatus.IN_STOCK, Set.of(ItemStatus.RESERVED, ItemStatus.UNSERVICEABLE),
                ItemStatus.RESERVED, Set.of(ItemStatus.IN_STOCK, ItemStatus.ISSUED),
                ItemStatus.ISSUED, Set.of(ItemStatus.INSTALLED, ItemStatus.UNSERVICEABLE),
                ItemStatus.INSTALLED, Set.of(ItemStatus.UNSERVICEABLE),
                ItemStatus.UNSERVICEABLE, Set.of(ItemStatus.IN_STOCK));
            if (!allowed.get(status).contains(target))
                throw new IllegalStateException("Cannot change item from " + status + " to " + target);
            status = target;
        }
    }

    static final class ReqLine {
        final String partNumber; final int quantity;
        ReqLine(String p, int q) { partNumber = p; quantity = q; }
    }
    static final class Requisition {
        final String id, technician, tail;
        final Priority priority;
        final List<ReqLine> lines = new ArrayList<>();
        final Map<String, List<String>> allocated = new HashMap<>();
        ReqStatus status = ReqStatus.RAISED;
        Requisition(String id, String technician, String tail, Priority priority, ReqLine line) {
            this.id = id; this.technician = technician; this.tail = tail; this.priority = priority;
            lines.add(line);
        }
        void transition(ReqStatus target) {
            Map<ReqStatus, Set<ReqStatus>> allowed = Map.of(
                ReqStatus.RAISED, Set.of(ReqStatus.APPROVED, ReqStatus.REJECTED),
                ReqStatus.APPROVED, Set.of(ReqStatus.FULFILLED, ReqStatus.WAITING_STOCK),
                ReqStatus.WAITING_STOCK, Set.of(ReqStatus.APPROVED, ReqStatus.REJECTED),
                ReqStatus.REJECTED, Set.of(), ReqStatus.FULFILLED, Set.of());
            if (!allowed.get(status).contains(target))
                throw new IllegalStateException("Cannot change requisition from " + status + " to " + target);
            status = target;
        }
    }
    static final class AOGEvent {
        final String id, tail, cause; LocalDateTime start;
        LocalDateTime end; String resolvedBy;
        AOGEvent(String id, String tail, String cause) {
            this.id = id; this.tail = tail; this.cause = cause; this.start = LocalDateTime.now();
        }
        double durationHours() { return Duration.between(start, end).toMinutes() / 60.0; }
    }
    static final class Proposal {
        final String part; final int quantity; final String policy;
        String status = "OPEN";
        Proposal(String part, int quantity, String policy) {
            this.part = part; this.quantity = quantity; this.policy = policy;
        }
    }

    // Strategy pattern
    interface ReorderPolicy { int quantity(int onHand, Part part); String name(); }
    static final class FixedLevelPolicy implements ReorderPolicy {
        public int quantity(int onHand, Part p) { return Math.max(0, p.reorderLevel - onHand); }
        public String name() { return "Fixed level"; }
    }
    static final class MinMaxPolicy implements ReorderPolicy {
        public int quantity(int onHand, Part p) { return onHand < p.minimum ? Math.max(0, p.maximum - onHand) : 0; }
        public String name() { return "Minimum-maximum"; }
    }
    interface ReportStrategy { String render(InventoryService service); }
    static final class StockReport implements ReportStrategy {
        public String render(InventoryService service) { return service.stockReport(); }
    }
    static final class AOGReport implements ReportStrategy {
        public String render(InventoryService service) { return service.aogReport(); }
    }
    static final class PolicyMetrics {
        final String policy; final int stockouts; final double meanAogHours;
        PolicyMetrics(String policy,int stockouts,double meanAogHours){this.policy=policy;this.stockouts=stockouts;this.meanAogHours=meanAogHours;}
    }
    static PolicyMetrics simulate(ReorderPolicy policy) {
        int[] demandDays={18,28,43,46,81,86,108,128,157,184,297,310};
        int[] demandQuantities={4,6,4,7,6,7,5,3,5,4,5,3};
        Part part=new Part("SIM-1","Simulated spare",100,false,4,"consumable",2,8);
        int onHand=3,arrivalDay=-1,arrivalQuantity=0,stockouts=0,demandIndex=0,openAogStart=-1;
        List<Double> completedHours=new ArrayList<>();
        final int leadTimeDays=30;
        for(int day=0;day<360;day++) {
            if(day==arrivalDay) {
                onHand+=arrivalQuantity;arrivalDay=-1;arrivalQuantity=0;
                if(openAogStart>=0){completedHours.add((day-openAogStart)*24.0);openAogStart=-1;}
            }
            int demand=0;
            if(demandIndex<demandDays.length&&day==demandDays[demandIndex])demand=demandQuantities[demandIndex++];
            if(demand>onHand) { stockouts++;if(openAogStart<0)openAogStart=day;onHand=0; }
            else onHand-=demand;
            if(arrivalDay<0) {int order=policy.quantity(onHand,part);if(order>0){arrivalDay=day+leadTimeDays;arrivalQuantity=order;}}
        }
        double mean=completedHours.isEmpty()?0:completedHours.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new PolicyMetrics(policy.name(),stockouts,mean);
    }

    // Repository: CSV persistence is kept out of domain classes.
    static final class InventoryRepository {
        final Path directory;
        final Map<String, Part> parts = new LinkedHashMap<>();
        final List<StockItem> items = new ArrayList<>();
        final List<Requisition> requisitions = new ArrayList<>();
        final List<AOGEvent> events = new ArrayList<>();
        final List<Proposal> proposals = new ArrayList<>();
        final List<String> audit = new ArrayList<>();
        int nextReq = 1, nextAog = 1;
        InventoryRepository() { this(DATA); }
        InventoryRepository(Path directory) {
            this.directory=directory;
            try { Files.createDirectories(directory); } catch (IOException e) { throw new RuntimeException(e); }
            Path catalog = directory.resolve("parts.csv"), stock = directory.resolve("stock.csv");
            if (Files.exists(catalog) && Files.exists(stock)) { load(catalog, stock); loadWorkflowData(); }
            else seed(catalog, stock);
        }
        private void seed(Path catalog, Path stock) {
            parts.put("FLT-101", new Part("FLT-101", "Hydraulic filter", 85, false, 4, "consumable", 2, 8));
            parts.put("ROT-220", new Part("ROT-220", "Fuel control unit", 2400, true, 2, "rotable", 1, 4));
            parts.put("ELE-330", new Part("ELE-330", "Navigation relay", 310, true, 3, "consumable", 2, 7));
            for (int i=1; i<=3; i++) items.add(new StockItem("FLT-101", String.format("FLT-101-%03d", i)));
            for (int i=1; i<=2; i++) items.add(new StockItem("ELE-330", String.format("ELE-330-%03d", i)));
            save(catalog, stock);
        }
        private void load(Path catalog, Path stock) {
            try {
                for (String row : Files.readAllLines(catalog)) {
                    if (row.isBlank() || row.startsWith("number,")) continue;
                    String[] x = row.split(",", -1);
                    parts.put(x[0], new Part(x[0], x[1], Double.parseDouble(x[2]), Boolean.parseBoolean(x[3]),
                        Integer.parseInt(x[4]), x[5], Integer.parseInt(x[6]), Integer.parseInt(x[7])));
                }
                for (String row : Files.readAllLines(stock)) {
                    if (row.isBlank() || row.startsWith("partNumber,")) continue;
                    String[] x = row.split(",", -1);
                    StockItem item = new StockItem(x[0], x[1]); item.status = ItemStatus.valueOf(x[2]); items.add(item);
                }
            } catch (Exception e) { throw new RuntimeException("Could not read CSV inventory in " + directory, e); }
        }
        void save() { save(directory.resolve("parts.csv"), directory.resolve("stock.csv")); saveWorkflowData(); }
        private void save(Path catalog, Path stock) {
            try {
                List<String> p = new ArrayList<>(List.of("number,description,unitCost,critical,reorderLevel,kind,minimum,maximum"));
                for (Part x : parts.values()) p.add(String.join(",", x.number, csv(x.description),
                    Double.toString(x.unitCost), Boolean.toString(x.critical), Integer.toString(x.reorderLevel),
                    x.kind, Integer.toString(x.minimum), Integer.toString(x.maximum)));
                Files.write(catalog, p);
                List<String> s = new ArrayList<>(List.of("partNumber,serial,status"));
                for (StockItem x : items) s.add(x.partNumber + "," + x.serial + "," + x.status);
                Files.write(stock, s);
            } catch (IOException e) { throw new RuntimeException("Could not save inventory CSV", e); }
        }
        private static String csv(String s) { return s.replace(",", " "); }
        private void loadWorkflowData() {
            try {
                Path f=directory.resolve("requisitions.csv");
                if(Files.exists(f)) for(String row:Files.readAllLines(f)) {
                    if(row.isBlank()||row.startsWith("id,")) continue;
                    String[] x=row.split(",",-1); String[] ls=x[5].split("\\|"); String[] first=ls[0].split(":",2);
                    Requisition r=new Requisition(x[0],decode(x[1]),decode(x[2]),Priority.valueOf(x[3]),new ReqLine(first[0],Integer.parseInt(first[1])));
                    for(int i=1;i<ls.length;i++){String[] v=ls[i].split(":",2);r.lines.add(new ReqLine(v[0],Integer.parseInt(v[1])));}
                    r.status=ReqStatus.valueOf(x[4]);
                    if(x.length>6&&!x[6].isEmpty())for(String a:x[6].split("\\|")){String[] v=a.split(":",2);r.allocated.put(v[0],new ArrayList<>(Arrays.asList(v[1].split(";"))));}
                    requisitions.add(r); nextReq=Math.max(nextReq,Integer.parseInt(r.id.substring(4))+1);
                }
                f=directory.resolve("aog-events.csv");
                if(Files.exists(f))for(String row:Files.readAllLines(f)){if(row.isBlank()||row.startsWith("id,"))continue;String[] x=row.split(",",-1);
                    AOGEvent e=new AOGEvent(x[0],decode(x[1]),decode(x[2]));e.start=LocalDateTime.parse(x[3]);if(!x[4].isEmpty())e.end=LocalDateTime.parse(x[4]);if(!x[5].isEmpty())e.resolvedBy=decode(x[5]);events.add(e);nextAog=Math.max(nextAog,Integer.parseInt(e.id.substring(4))+1);}
                f=directory.resolve("proposals.csv");
                if(Files.exists(f))for(String row:Files.readAllLines(f)){if(row.isBlank()||row.startsWith("part,"))continue;String[] x=row.split(",",-1);Proposal p=new Proposal(x[0],Integer.parseInt(x[1]),decode(x[2]));p.status=x[3];proposals.add(p);}
                f=directory.resolve("audit.csv");if(Files.exists(f))audit.addAll(Files.readAllLines(f));
            } catch(Exception e){throw new RuntimeException("Could not load saved workflow data",e);}
        }
        private void saveWorkflowData() {
            try {
                List<String> rows=new ArrayList<>(List.of("id,technician,tail,priority,status,lines,allocated"));
                for(Requisition r:requisitions){StringJoiner ls=new StringJoiner("|");for(ReqLine l:r.lines)ls.add(l.partNumber+":"+l.quantity);StringJoiner as=new StringJoiner("|");r.allocated.forEach((p,ss)->as.add(p+":"+String.join(";",ss)));rows.add(r.id+","+encode(r.technician)+","+encode(r.tail)+","+r.priority+","+r.status+","+ls+","+as);}
                Files.write(directory.resolve("requisitions.csv"),rows);
                rows=new ArrayList<>(List.of("id,tail,cause,start,end,resolvedBy"));for(AOGEvent e:events)rows.add(e.id+","+encode(e.tail)+","+encode(e.cause)+","+e.start+","+(e.end==null?"":e.end)+","+(e.resolvedBy==null?"":encode(e.resolvedBy)));Files.write(directory.resolve("aog-events.csv"),rows);
                rows=new ArrayList<>(List.of("part,quantity,policy,status"));for(Proposal p:proposals)rows.add(p.part+","+p.quantity+","+encode(p.policy)+","+p.status);Files.write(directory.resolve("proposals.csv"),rows);
            }catch(IOException e){throw new RuntimeException("Could not save workflow data",e);}
        }
        private static String encode(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        private static String decode(String s){return new String(Base64.getUrlDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8);}
    }

    // Facade, controller, and Observer-style notifications
    static final class InventoryService {
        final InventoryRepository repo;
        ReorderPolicy policy = new FixedLevelPolicy();
        final List<java.util.function.Consumer<String>> listeners = new ArrayList<>();
        final Map<Role, Set<String>> permissions = Map.of(
            Role.STORE_KEEPER, Set.of("catalogue","receive","issue","return","reports","proposals"),
            Role.TECHNICIAN, Set.of("requisition","aog","reports"),
            Role.PLANNER, Set.of("approve","policy","proposals","reports"),
            Role.COMMANDER, Set.of("approve","aog","reports"));
        InventoryService(InventoryRepository repo) { this.repo = repo; }
        void allow(Role role, String action) {
            if (!permissions.get(role).contains(action)) throw new SecurityException("Role " + role + " cannot perform " + action);
        }
        void log(String user, String action, String detail) {
            repo.audit.add(LocalDateTime.now() + "," + user + "," + action + "," + detail);
            try { Files.write(repo.directory.resolve("audit.csv"), repo.audit); } catch (IOException e) { throw new RuntimeException(e); }
            repo.save();
        }
        int available(String part) { return (int)repo.items.stream().filter(i -> i.partNumber.equals(part) && i.status == ItemStatus.IN_STOCK).count(); }
        void addPart(String user, Role role, Part p) {
            allow(role,"catalogue"); if (repo.parts.containsKey(p.number)) throw new IllegalArgumentException("Part number already exists");
            repo.parts.put(p.number,p); log(user,"add_part",p.number);
        }
        void updatePart(String user,Role role,Part updated) {
            allow(role,"catalogue");Part old=requirePart(updated.number);
            repo.parts.put(updated.number,new Part(updated.number,updated.description,updated.unitCost,updated.critical,
                updated.reorderLevel,old.kind,updated.minimum,updated.maximum));
            log(user,"update_part",updated.number);
        }
        void receive(String user, Role role, String partNo, int qty) {
            allow(role,"receive"); Part p = requirePart(partNo); if (qty < 1) throw new IllegalArgumentException("Quantity must be positive");
            // Factory Method choice: serial format follows the catalogue part type.
            long count = repo.items.stream().filter(i -> i.partNumber.equals(partNo)).count()+1;
            String prefix = p.kind.equalsIgnoreCase("rotable") ? "RB" : "CB";
            for (int i=0;i<qty;i++) repo.items.add(new StockItem(partNo, prefix+"-"+partNo+"-"+String.format("%03d",count++)));
            log(user,"receive",partNo+" x"+qty);
        }
        Requisition raise(String user, Role role, String tail, Priority priority, List<ReqLine> lines, boolean grounded) {
            allow(role,"requisition"); if(lines.isEmpty())throw new IllegalArgumentException("Add at least one requisition line");
            Map<String,Integer> totals=new LinkedHashMap<>(); boolean critical=false, enough=true;
            for(ReqLine l:lines){requirePart(l.partNumber);if(l.quantity<1)throw new IllegalArgumentException("Quantity must be positive");totals.merge(l.partNumber,l.quantity,Integer::sum);critical|=requirePart(l.partNumber).critical;}
            for(Map.Entry<String,Integer> e:totals.entrySet())if(available(e.getKey())<e.getValue()) {
                if(priority==Priority.AOG) reclaimRoutineReservations(user,e.getKey(),e.getValue()-available(e.getKey()));
                if(available(e.getKey())<e.getValue())enough=false;
            }
            ReqLine first=lines.get(0);
            Requisition r = new Requisition(String.format("REQ-%04d",repo.nextReq++), user, tail, priority, first);
            for(int i=1;i<lines.size();i++)r.lines.add(lines.get(i));
            repo.requisitions.add(r);
            if (enough && !critical) { for(Map.Entry<String,Integer> e:totals.entrySet())reserve(r,e.getKey(),e.getValue()); r.transition(ReqStatus.APPROVED); }
            else {
                if (grounded) declareInternal(tail,totals.keySet().toString());
                for(String part:totals.keySet())propose(part);
                if (critical) notifyAll("Critical requisition "+r.id+" requires commander approval.");
            }
            log(user,"raise_requisition",r.id+" lines="+lines.size()); return r;
        }
        void approve(String user, Role role, String id, boolean yes) {
            allow(role,"approve"); Requisition r = requireReq(id);
            if (r.status != ReqStatus.RAISED && r.status != ReqStatus.WAITING_STOCK) throw new IllegalStateException("Requisition is not awaiting a decision");
            if (r.lines.stream().anyMatch(l -> requirePart(l.partNumber).critical) && role != Role.COMMANDER)
                throw new SecurityException("Critical part requires commander approval");
            if (yes) {
                Map<String,Integer> totals=new LinkedHashMap<>();for(ReqLine l:r.lines)totals.merge(l.partNumber,l.quantity,Integer::sum);
                for(Map.Entry<String,Integer> e:totals.entrySet())if(available(e.getKey())<e.getValue())throw new IllegalStateException("Insufficient available stock");
                for(Map.Entry<String,Integer> e:totals.entrySet())reserve(r,e.getKey(),e.getValue());
                r.transition(ReqStatus.APPROVED);
            } else r.transition(ReqStatus.REJECTED);
            log(user,yes?"approve":"reject",id);
        }
        private void reserve(Requisition r,String part,int qty) {
            List<String> picked=new ArrayList<>();
            for (StockItem i:repo.items) if (i.partNumber.equals(part)&&i.status==ItemStatus.IN_STOCK&&picked.size()<qty) {
                i.transition(ItemStatus.RESERVED); picked.add(i.serial);
            }
            r.allocated.put(part,picked);
        }
        private void reclaimRoutineReservations(String user,String part,int needed) {
            for(Requisition old:repo.requisitions) {
                if(needed==0)break;
                if(old.priority!=Priority.ROUTINE||old.status!=ReqStatus.APPROVED)continue;
                List<String> serials=old.allocated.get(part);if(serials==null)continue;
                while(needed>0&&!serials.isEmpty()) {
                    String serial=serials.remove(serials.size()-1);item(serial).transition(ItemStatus.IN_STOCK);needed--;
                }
                for(List<String> remaining:old.allocated.values())for(String serial:remaining) {
                    StockItem reserved=item(serial);if(reserved.status==ItemStatus.RESERVED)reserved.transition(ItemStatus.IN_STOCK);
                }
                old.allocated.clear();
                old.status=ReqStatus.WAITING_STOCK;
                log(user,"reservation_preempted",old.id+" part="+part);
            }
        }
        void issue(String user,Role role,String id) {
            allow(role,"issue"); Requisition r=requireReq(id); if(r.status!=ReqStatus.APPROVED) throw new IllegalStateException("Approve before issuing");
            for(List<String> serials:r.allocated.values()) for(String serial:serials) item(serial).transition(ItemStatus.ISSUED);
            r.transition(ReqStatus.FULFILLED);for(String p:r.allocated.keySet())propose(p);log(user,"issue",id);
        }
        void fit(String user,Role role,String serial) {
            allow(role,"aog"); StockItem i=item(serial); i.transition(ItemStatus.INSTALLED);
            String aircraft = repo.requisitions.stream()
                .filter(r -> r.allocated.values().stream().anyMatch(xs -> xs.contains(i.serial)))
                .map(r -> r.tail).findFirst().orElse(null);
            if (aircraft != null) repo.events.stream()
                .filter(e -> e.tail.equals(aircraft) && e.end == null).findFirst()
                .ifPresent(e -> { e.end=LocalDateTime.now(); e.resolvedBy=serial; });
            log(user,"fit",serial);
        }
        void unserviceable(String user,Role role,String serial) { allow(role,"aog"); item(serial).transition(ItemStatus.UNSERVICEABLE); log(user,"unserviceable",serial); }
        void repairReturn(String user,Role role,String serial) { allow(role,"return"); item(serial).transition(ItemStatus.IN_STOCK); log(user,"repair_return",serial); }
        void declareAog(String user,Role role,String tail,String cause) { allow(role,"aog"); declareInternal(tail,cause); log(user,"declare_aog",tail+" "+cause); }
        private void declareInternal(String tail,String cause) {
            if(repo.events.stream().noneMatch(e->e.tail.equals(tail)&&e.end==null)) {
                AOGEvent e=new AOGEvent(String.format("AOG-%04d",repo.nextAog++),tail,cause); repo.events.add(e);
                notifyAll("AOG event "+e.id+" opened for "+tail);
            }
        }
        private void propose(String partNo) {
            if(repo.proposals.stream().anyMatch(p->p.part.equals(partNo)&&(p.status.equals("OPEN")||p.status.equals("APPROVED")))) return;
            Part p=requirePart(partNo); int q=policy.quantity(available(partNo),p);
            if(q>0) { repo.proposals.add(new Proposal(partNo,q,policy.name())); notifyAll("Reorder proposal: "+partNo+" x"+q+" ("+policy.name()+")"); }
        }
        void setPolicy(String user,Role role,String choice) {
            allow(role,"policy"); policy=switch(choice.toLowerCase()) { case "fixed" -> new FixedLevelPolicy(); case "minmax" -> new MinMaxPolicy(); default -> throw new IllegalArgumentException("Choose fixed or minmax"); };
            log(user,"set_policy",policy.name());
        }
        void receiveProposal(String user,Role role,String partNo) {
            allow(role,"receive"); Proposal p=repo.proposals.stream().filter(x->x.part.equals(partNo)&&x.status.equals("APPROVED")).findFirst().orElseThrow(()->new IllegalArgumentException("No approved proposal"));
            receive(user,role,partNo,p.quantity); p.status="RECEIVED"; repo.save();
        }
        void decideProposal(String user,Role role,String partNo,boolean approve) {
            allow(role,"proposals");Proposal p=repo.proposals.stream().filter(x->x.part.equals(partNo)&&x.status.equals("OPEN")).findFirst().orElseThrow(()->new IllegalArgumentException("No open proposal"));
            p.status=approve?"APPROVED":"REVISION";log(user,approve?"approve_proposal":"return_proposal",partNo);
        }
        String stockReport() {
            StringBuilder b=new StringBuilder("STOCK STATUS\nPart | Description | In stock | Reserved | Issued | Unserviceable\n");
            for(Part p:repo.parts.values()) b.append(p.number).append(" | ").append(p.description).append(" | ")
                .append(count(p.number,ItemStatus.IN_STOCK)).append(" | ").append(count(p.number,ItemStatus.RESERVED)).append(" | ")
                .append(count(p.number,ItemStatus.ISSUED)).append(" | ").append(count(p.number,ItemStatus.UNSERVICEABLE)).append('\n');
            return b.toString();
        }
        String aogReport() {
            StringBuilder b=new StringBuilder("AOG DURATION REPORT\nEvent | Tail | Cause | Status | Duration hours\n"); double total=0; int closed=0;
            for(AOGEvent e:repo.events) { b.append(e.id).append(" | ").append(e.tail).append(" | ").append(e.cause).append(" | ");
                if(e.end==null) b.append("OPEN | ongoing\n"); else { double h=e.durationHours(); total+=h;closed++;b.append(String.format("CLOSED | %.2f%n",h)); }
            }
            if(closed>0)b.append(String.format("Mean closed-event duration: %.2f hours%n",total/closed)); return b.toString();
        }
        String report(String user,Role role,String kind) {
            allow(role,"reports"); ReportStrategy strategy=kind.equals("stock")?new StockReport():new AOGReport();String text=strategy.render(this);
            try { Files.createDirectories(REPORTS); Files.writeString(REPORTS.resolve(kind+"_report.txt"),text); }
            catch(IOException e){throw new RuntimeException(e);} log(user,"report",kind); return text+"Saved under reports/\n";
        }
        private int count(String p,ItemStatus s){return (int)repo.items.stream().filter(i->i.partNumber.equals(p)&&i.status==s).count();}
        private Part requirePart(String p){Part x=repo.parts.get(p);if(x==null)throw new IllegalArgumentException("Part not found: "+p);return x;}
        private Requisition requireReq(String id){return repo.requisitions.stream().filter(r->r.id.equalsIgnoreCase(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Requisition not found"));}
        private StockItem item(String serial){return repo.items.stream().filter(i->i.serial.equalsIgnoreCase(serial)).findFirst().orElseThrow(()->new IllegalArgumentException("Stock item not found"));}
        private void notifyAll(String message){listeners.forEach(l->l.accept(message));}
    }

    private static final Map<String,Role> USERS=Map.of("keeper",Role.STORE_KEEPER,"tech",Role.TECHNICIAN,"planner",Role.PLANNER,"commander",Role.COMMANDER);
    public static void main(String[] args) {
        service=new InventoryService(new InventoryRepository()); service.listeners.add(m->System.out.println("[Notification] "+m));
        System.out.println("Military Aviation Spare Parts Inventory System (sample data)");
        System.out.println("Demo users: keeper, tech, planner, commander (prototype role check; no passwords)");
        while(true) {
            System.out.print("\nLogin or quit: "); String user=IN.nextLine().trim().toLowerCase();
            if(user.equals("quit")||user.equals("q"))break;
            Role role=USERS.get(user); if(role==null){System.out.println("Unknown user.");continue;}
            System.out.println("Logged in as "+user+" ["+role+"]");
            while(true) {
                menu(); String choice=IN.nextLine().trim(); if(choice.equals("0"))break;
                try { execute(user,role,choice); }
                catch(Exception e){System.out.println("Error: "+e.getMessage());}
            }
        }
        System.out.println("Goodbye.");
    }
    private static void menu(){
        System.out.println("\n1 List parts/stock  2 Raise requisition  3 Approve/reject requisition");
        System.out.println("4 Receive stock  5 Issue approved requisition  6 Fit installed item");
        System.out.println("7 Declare AOG  8 Mark unserviceable  9 Repair return  10 Set reorder policy");
        System.out.println("11 View proposals  12 Approve/return proposal  13 Stock report  14 AOG report");
        System.out.println("15 View requisitions  16 Add part  17 Receive approved proposal  18 Update part");
        System.out.println("19 Compare reorder policies  0 Logout"); System.out.print("Select: ");
    }
    private static void execute(String user,Role role,String c) {
        switch(c) {
            case "1" -> { for(Part p:service.repo.parts.values())System.out.printf("%s: %s available=%d critical=%s type=%s%n",p.number,p.description,service.available(p.number),p.critical,p.kind);
                for(StockItem i:service.repo.items)System.out.println("  "+i.serial+" "+i.partNumber+" ["+i.status+"]"); }
            case "2" -> { System.out.print("Tail: ");String tail=IN.nextLine().trim().toUpperCase();System.out.print("Priority routine/urgent/aog: ");Priority pr=Priority.valueOf(IN.nextLine().trim().toUpperCase());
                System.out.print("Number of requisition lines: ");int n=Integer.parseInt(IN.nextLine());List<ReqLine> lines=new ArrayList<>();
                for(int i=1;i<=n;i++){System.out.print("Line "+i+" part number: ");String p=IN.nextLine().trim().toUpperCase();System.out.print("Line "+i+" quantity: ");lines.add(new ReqLine(p,Integer.parseInt(IN.nextLine())));}
                System.out.print("Aircraft grounded? y/n: ");boolean g=IN.nextLine().trim().equalsIgnoreCase("y");
                Requisition r=service.raise(user,role,tail,pr,lines,g);System.out.println("Created "+r.id+" ["+r.status+"]"); }
            case "3" -> {System.out.print("Requisition ID: ");String id=IN.nextLine().trim();System.out.print("Approve? y/n: ");service.approve(user,role,id,IN.nextLine().trim().equalsIgnoreCase("y"));System.out.println("Decision recorded.");}
            case "4" -> {System.out.print("Part number: ");String p=IN.nextLine().trim().toUpperCase();System.out.print("Quantity: ");service.receive(user,role,p,Integer.parseInt(IN.nextLine()));System.out.println("Stock received.");}
            case "5" -> {System.out.print("Requisition ID: ");service.issue(user,role,IN.nextLine().trim());System.out.println("Stock issued.");}
            case "6" -> {System.out.print("Issued item serial: ");service.fit(user,role,IN.nextLine().trim());System.out.println("Item fitted; open AOG event closed.");}
            case "7" -> {System.out.print("Aircraft tail: ");String tail=IN.nextLine().trim().toUpperCase();System.out.print("Cause: ");service.declareAog(user,role,tail,IN.nextLine().trim());System.out.println("AOG recorded.");}
            case "8" -> {System.out.print("Item serial: ");service.unserviceable(user,role,IN.nextLine().trim());System.out.println("Marked unserviceable.");}
            case "9" -> {System.out.print("Repaired item serial: ");service.repairReturn(user,role,IN.nextLine().trim());System.out.println("Returned to stock.");}
            case "10" -> {System.out.print("Policy fixed/minmax: ");service.setPolicy(user,role,IN.nextLine().trim());System.out.println("Policy: "+service.policy.name());}
            case "11" -> {service.allow(role,"proposals");for(Proposal p:service.repo.proposals)System.out.println(p.part+" qty="+p.quantity+" policy="+p.policy+" status="+p.status);}
            case "12" -> {System.out.print("Part number: ");String p=IN.nextLine().trim().toUpperCase();System.out.print("Approve proposal? y/n: ");service.decideProposal(user,role,p,IN.nextLine().trim().equalsIgnoreCase("y"));System.out.println("Proposal decision recorded.");}
            case "13" -> System.out.print(service.report(user,role,"stock"));
            case "14" -> System.out.print(service.report(user,role,"aog"));
            case "15" -> {for(Requisition r:service.repo.requisitions)System.out.println(r.id+" tail="+r.tail+" priority="+r.priority+" status="+r.status+" lines="+r.lines.size());}
            case "16" -> {System.out.print("Part number: ");String n=IN.nextLine().trim().toUpperCase();System.out.print("Description: ");String d=IN.nextLine().trim();System.out.print("Unit cost: ");double cost=Double.parseDouble(IN.nextLine());
                System.out.print("Critical y/n: ");boolean critical=IN.nextLine().trim().equalsIgnoreCase("y");System.out.print("Reorder level: ");int level=Integer.parseInt(IN.nextLine());System.out.print("Type consumable/rotable: ");String kind=IN.nextLine().trim();System.out.print("Minimum: ");int min=Integer.parseInt(IN.nextLine());System.out.print("Maximum: ");int max=Integer.parseInt(IN.nextLine());
                service.addPart(user,role,new Part(n,d,cost,critical,level,kind,min,max));System.out.println("Part added.");}
            case "17" -> {System.out.print("Part number of approved proposal: ");service.receiveProposal(user,role,IN.nextLine().trim().toUpperCase());System.out.println("Proposal received.");}
            case "18" -> {System.out.print("Existing part number: ");String n=IN.nextLine().trim().toUpperCase();System.out.print("Description: ");String d=IN.nextLine().trim();System.out.print("Unit cost: ");double cost=Double.parseDouble(IN.nextLine());
                System.out.print("Critical y/n: ");boolean critical=IN.nextLine().trim().equalsIgnoreCase("y");System.out.print("Reorder level: ");int level=Integer.parseInt(IN.nextLine());System.out.print("Minimum: ");int min=Integer.parseInt(IN.nextLine());System.out.print("Maximum: ");int max=Integer.parseInt(IN.nextLine());
                Part existing=service.repo.parts.get(n);if(existing==null)throw new IllegalArgumentException("Part number not found");
                service.updatePart(user,role,new Part(n,d,cost,critical,level,existing.kind,min,max));System.out.println("Part updated.");}
            case "19" -> {PolicyMetrics fixed=simulate(new FixedLevelPolicy()),minmax=simulate(new MinMaxPolicy());
                System.out.println("POLICY COMPARISON (12-month synthetic demand; opening stock=3; 30-day replenishment lead time)");
                System.out.println("Policy | Stock-out events | Mean AOG duration (hours)");
                System.out.printf("%s | %d | %.1f%n",fixed.policy,fixed.stockouts,fixed.meanAogHours);
                System.out.printf("%s | %d | %.1f%n",minmax.policy,minmax.stockouts,minmax.meanAogHours);}
            default -> System.out.println("Unknown menu option.");
        }
    }
}
