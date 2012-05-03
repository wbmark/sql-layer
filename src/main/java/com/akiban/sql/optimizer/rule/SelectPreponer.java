/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule;

import com.akiban.sql.optimizer.plan.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Move WHERE clauses closer to their table origin.
 * This rule runs after flattening has been laid out.
 *
 * Note: <i>prepone</i>, while not an American or British English
 * word, is the transparent opposite of <i>postpone</i>.
 */
// TODO: Something similar is needed to handle moving HAVING
// conditions on the group by fields across the aggregation boundary
// and WHERE conditions on subqueries (views) into the subquery
// itself. These need to run earlier to affect indexing. Not sure how
// to integrate all these. Maybe move everything earlier on and then
// recognize joins of such filtered tables as Joinable.
public class SelectPreponer extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(SelectPreponer.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {
        TableOriginFinder finder = new TableOriginFinder();
        finder.find(plan.getPlan());
        Preponer preponer = new Preponer();
        for (PlanNode origin : finder.getOrigins()) {
            preponer.addOrigin(origin);
        }
        preponer.moveDeferred();
    }
    
    /** Find all the places where data starts, such as <code>IndexScan</code> and <code><i>XxxLookup</i></code>. */
    static class TableOriginFinder implements PlanVisitor, ExpressionVisitor {
        List<PlanNode> origins = new ArrayList<PlanNode>();

        public void find(PlanNode root) {
            root.accept(this);
        }

        public List<PlanNode> getOrigins() {
            return origins;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            if (n instanceof IndexScan) {
                origins.add(n);
            }
            else if (n instanceof TableLoader) {
                if (n instanceof BasePlanWithInput) {
                    PlanNode input = ((BasePlanWithInput)n).getInput();
                    if (!((input instanceof TableLoader) ||
                          (input instanceof IndexScan))) {
                        // Will put input in, so don't bother putting both in.
                        origins.add(n);
                    }
                }
                else {
                    origins.add(n);
                }
            }
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            return true;
        }
    }

    /** Holds the state of a single side of a loop, which usually means a group. */
    static class Loop {
        Map<TableSource,PlanNode> loaders; // Lookup operators.
        Map<ExpressionNode,PlanNode> indexColumns; // Individual columns of IndexScan.
        List<PlanNode> flattens; // Flatten & Product operators that do in-group join.
        Map<PlanNode,Set<TableSource>> flattened; // Tables that participate in those.
        
        public Loop() {
            loaders = new HashMap<TableSource,PlanNode>();
        }

        public void setIndex(IndexScan index) {
            indexColumns = new HashMap<ExpressionNode,PlanNode>();
            for (ExpressionNode column : index.getColumns()) {
                if (column != null) {
                    indexColumns.put(column, index);
                }
            }
        }

        public void addLoader(PlanNode loader) {
            for (TableSource table : ((TableLoader)loader).getTables()) {
                loaders.put(table, loader);
            }
        }

        /** Add a within-group join: Flatten or Product. */
        public void addFlattenOrProduct(PlanNode join) {
            if (flattens == null)
                flattens = new ArrayList<PlanNode>();
            flattens.add(join);

            // Might be able to place multi-table conditions after a join.
            if (flattened == null)
                flattened = new HashMap<PlanNode,Set<TableSource>>();
            Set<TableSource> tables = new HashSet<TableSource>(loaders.keySet());
            flattened.put(join, tables);
        }

        public void addFlatten(Flatten flatten) {
            // Limit to tables that are inner joined (and on the outer
            // side of outer joins.)
            Set<TableSource> inner = flatten.getInnerJoinedTables();
            loaders.keySet().retainAll(inner);
            if (indexColumns != null) {
                Iterator<ExpressionNode> iter = indexColumns.keySet().iterator();
                while (iter.hasNext()) {
                    ExpressionNode expr = iter.next();
                    if (expr.isColumn() && 
                        !inner.contains(((ColumnExpression)expr).getTable()))
                        iter.remove();
                }
            }
            addFlattenOrProduct(flatten);
        }

        /** Merge another loop into this one. Although <code>Product</code> starts
         * with separate lookup operators, it's a single loop for purposes of nesting. */
        public Loop merge(Loop other, PlanNode before) {
            loaders.putAll(other.loaders);
            if (indexColumns == null)
                indexColumns = other.indexColumns;
            else if (other.indexColumns != null)
                indexColumns.putAll(other.indexColumns);
            if (flattens == null)
                flattens = other.flattens;
            else if (other.flattens != null) {
                int i = -1;
                if (before != null)
                    i = flattens.indexOf(before);
                if (i < 0)
                    i = flattens.size();
                for (PlanNode flatten : other.flattens) {
                    if (flatten == before) 
                        break;
                    flattens.add(i++, flatten);
                }
            }
            if (flattened == null)
                flattened = other.flattened;
            else if (other.flattened != null) {
                for (Map.Entry<PlanNode,Set<TableSource>> entry : other.flattened.entrySet()) {
                    Set<TableSource> existing = flattened.get(entry.getKey());
                    if (existing != null)
                        existing.addAll(entry.getValue());
                    else
                        flattened.put(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /** Does this loop have any interesting state? */
        public boolean isEmpty() {
            return ((flattens == null) ||
                    (loaders.isEmpty() &&
                     ((indexColumns == null) || indexColumns.isEmpty())));
        }

        /** Does this loop consist solely of an index? */
        public boolean indexOnly() {
            return (loaders.isEmpty() && 
                    !((indexColumns == null) || indexColumns.isEmpty()));
        }
    }

    /** Move conditions as follows:
     * 
     * Starting with index scans and lookup operators, trace
     * downstream, adding tables from additional such operators. When
     * we come to a <code>Product</code>, merge with any other
     * streams. When we come to a <code>MapJoin</code>, note the
     * traversal of its loops, which corresponds to bindings being
     * available to inner loops.
     *
     * When we finally come to a <code>Select</code>, move conditions from it down to
     * earlier operators:<ul>
     * <li>If the condition only uses columns from an index, right after the scan.</li>
     * <li>If the condition uses columns from a single table, right
     * after that table is looked up.</li>
     * <li>If the condition uses multiple tables in a single group, when they are joined
     * together by <code>Flatten</code> or <code>Product</code></li>
     * <li>Tables from outer loops using <code>MapJoin</code>, which are available to
     *  the inner loop, can be ignored in the above.</li></ul>
     * 
     * In general, nested loop handling needs to be deferred until all
     * the loops are recorded.
     */
    static class Preponer {
        Map<Product,Loop> products;
        Map<Select,SelectConditions> selects;
        
        public Preponer() {
        }

        /** Starting at the given node, trace downstream until get to
         * some conditions or something we can't handle. */
        public void addOrigin(PlanNode node) {
            Loop loop = new Loop();
            boolean newLoop = true, hasMaps = false, hasProducts = false;
            PlanNode prev = null;
            if (node instanceof IndexScan) {
                loop.setIndex((IndexScan)node);
                prev = node;
                node = node.getOutput();
            }
            while (node instanceof TableLoader) {
                loop.addLoader(node);
                prev = node;
                node = node.getOutput();
            }
            while (true) {
                if (node instanceof Flatten) {
                    // A Flatten takes a single stream of lookups.
                    loop.addFlatten((Flatten)node);
                }
                else if (node instanceof Product) {
                    Product product = (Product)node;
                    if (newLoop) {
                        // Always inner join at present, so no filtering
                        // of sources.
                        loop.addFlattenOrProduct(product);

                        // A Product takes multiple streams, so we may
                        // have seen this one before.
                        if (products == null)
                            products = new HashMap<Product,Loop>();
                        Loop oloop = products.get(product);
                        if (oloop != null) {
                            loop = oloop.merge(loop, product);
                            newLoop = false;
                        }
                        else {
                            products.put(product, loop);
                        }
                    }
                    hasProducts = true;
                }
                else if (node instanceof MapJoin) {
                    MapJoin map = (MapJoin)node;
                    switch (map.getJoinType()) {
                    case INNER:
                        break;
                    case LEFT:
                    case SEMI:
                        if (prev == map.getInner())
                            return;
                        break;
                    default:
                        return;
                    }
                    hasMaps = true;
                }
                else if (node instanceof Select) {
                    Select select = (Select)node;
                    if (!select.getConditions().isEmpty()) {
                        SelectConditions selectConditions = null;
                        boolean newSelect = false;
                        if (selects != null)
                            selectConditions = selects.get(select);
                        if (selectConditions == null) {
                            selectConditions = new SelectConditions(select);
                            newSelect = true;
                        }
                        if (!loop.isEmpty()) {
                            // Try once right away to get single table conditions.
                            selectConditions.moveConditions(loop);
                        }
                        if (select.getConditions().isEmpty()) {
                            if (!newSelect)
                                selects.remove(select);
                        }
                        else {
                            if (hasMaps && newLoop) {
                                selectConditions.addBranch(loop);
                            }
                            if (hasProducts || hasMaps) {
                                // Need to defer until have all the contributors
                                // to the Map joins. Enable reuse for
                                // Product.
                                if (selects == null)
                                    selects = new HashMap<Select,SelectConditions>();
                                selects.put(select, selectConditions);
                            }
                        }
                    }
                }
                else
                    break;
                prev = node;
                node = node.getOutput();
            }
        }

        public void moveDeferred() {
            if (selects != null) {
                for (SelectConditions swm : selects.values()) {
                    swm.moveConditions(null);
                }
            }
        }

    }

    /** Holds what is known about inputs to a Select, which may come
     * from multiple <code>MapJoin</code> loops. */
    static class SelectConditions {
        Select select;
        ConditionDependencyAnalyzer dependencies;
        // The loops that are joined up to feed the Select, added in visitor
        // order, meaning that tables from an earlier loop should be available as
        // bound variables to later / deeper ones.
        List<Loop> loops;

        public SelectConditions(Select select) {
            this.select = select;
            dependencies = new ConditionDependencyAnalyzer(select);
        }

        public void addBranch(Loop loop) {
            if (loops == null)
                loops = new ArrayList<Loop>();
            loops.add(loop);
        }

        public boolean hasBranches() {
            return (loops != null);
        }
        
        /** Try to move conditions from <code>Select</code>.
         * @param loop If non-null, have a straight path to these
         * conditions and know where tables came from.  See what can
         * be moved back there.
         */
        public void moveConditions(Loop loop) {
            Iterator<ConditionExpression> iter = select.getConditions().iterator();
            while (iter.hasNext()) {
                ConditionExpression condition = iter.next();
                ColumnSource singleTable = dependencies.analyze(condition);
                PlanNode moveTo = canMove(loop, singleTable);
                if ((moveTo != null) && (moveTo != select.getInput())) {
                    moveCondition(condition, moveTo);
                    iter.remove();
                }
            }
        }
        
        /** Return where this condition can move. */
        // TODO: Could move earlier after subset of joins by breaking apart Flatten.
        public PlanNode canMove(Loop loop, ColumnSource singleTable) {
            Set<TableSource> outerTables = null;
            if (loop == null) {
                // If the condition only references a single table, no
                // need to check outer bindings; it's wherever it is.
                if (singleTable == null)
                    outerTables = new HashSet<TableSource>();
                // Several joined loops: find the shallowest one that has everything.
                loop = findLoop(outerTables);
                if (loop == null)
                    return null;
            }
            if (loop.indexColumns != null) {
                // Can check the index column before it's used for lookup.
                PlanNode loader = getSingleIndexLoader(loop, outerTables);
                if (loader != null)
                    return loader;
            }
            Set<ColumnSource> allTables = dependencies.getReferencedTables();
            if ((singleTable == null) && (outerTables != null)) {
                // Might still narrow down to a single table within this loop.
                allTables.removeAll(outerTables);
                if (allTables.size() == 1)
                    singleTable = allTables.iterator().next();
            }
            if (singleTable != null) {
                return loop.loaders.get(singleTable);
            }
            if ((loop.flattens != null) && !allTables.isEmpty()) {
                flattens:
                for (PlanNode flatten : loop.flattens) {
                    // Find the first (deepest) flatten that has all the tables we need.
                    Set<TableSource> tables = loop.flattened.get(flatten);
                    for (ColumnSource table : allTables) {
                        if (!tables.contains(table))
                            continue flattens;
                    }
                    return flatten;
                }
            }
            return null;
        }

        /** Find the first loop that has enough to evaluate the condition. */
        public Loop findLoop(Set<TableSource> outerTables) {
            for (Loop loop : loops) {
                if (loop.indexOnly()) {
                    // If the map loop is just an index, have to
                    // look at individual columns.
                    Set<TableSource> maybeOuterTables = null;
                    if (outerTables != null)
                        // Even though index only has some columns, can exclude whole
                        // tables for purposes of deeper loops.
                        maybeOuterTables = new HashSet<TableSource>();
                    boolean allFound = true;
                    for (ColumnExpression column : dependencies.getReferencedColumns()) {
                        if (outerTables != null) {
                            if (outerTables.contains(column.getTable())) 
                                continue;
                        }
                        if (loop.indexColumns.containsKey(column)) {
                            if (maybeOuterTables != null)
                                maybeOuterTables.add((TableSource)column.getTable());
                        }
                        else {
                            allFound = false;
                        }
                    }
                    if (allFound)
                        return loop;
                    if (maybeOuterTables != null)
                        outerTables.addAll(maybeOuterTables);
                }
                else {
                    boolean allFound = true;
                    for (ColumnSource referencedTable : dependencies.getReferencedTables()) {
                        if (outerTables != null) {
                            if (outerTables.contains(referencedTable))
                                continue;
                        }
                        if (!loop.loaders.containsKey(referencedTable)) {
                            allFound = false;
                            break;
                        }
                    }
                    if (allFound)
                        return loop;
                    if (outerTables != null)
                        // Not moving to this loop; its tables are then available.
                        outerTables.addAll(loop.loaders.keySet());
                }
            }
            return null;
        }

        /** If all the referenced columns come from the same index, return it. */
        public PlanNode getSingleIndexLoader(Loop loop,
                                             Set<TableSource> outerTables) {
            PlanNode single = null;
            for (ColumnExpression column : dependencies.getReferencedColumns()) {
                if (outerTables != null) {
                    if (outerTables.contains(column.getTable())) 
                        continue;
                }
                PlanNode loader = loop.indexColumns.get(column);
                if (loader == null)
                    return null;
                if (single == null)
                    single = loader;
                else if (single != loader)
                    return null;
            }
            return single;
        }

        /** Move the given condition to a Select that is right after the given node. */
        public void moveCondition(ConditionExpression condition, PlanNode before) {
            Select select = null;
            PlanWithInput after = before.getOutput();
            if (after instanceof Select)
                select = (Select)after;
            else {
                select = new Select(before, new ConditionList(1));
                after.replaceInput(before, select);
            }
            select.getConditions().add(condition);
        }

    }

}
