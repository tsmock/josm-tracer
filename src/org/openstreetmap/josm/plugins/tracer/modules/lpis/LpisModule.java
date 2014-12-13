/**
 *  Tracer - plugin for JOSM
 *  Jan Bilak, Marian Kyral, Martin Svec
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program; if not, write to the Free Software Foundation, Inc.,
 *  51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openstreetmap.josm.plugins.tracer.modules.lpis;

import java.awt.Cursor;
import java.util.*;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.actions.search.SearchCompiler;
import org.openstreetmap.josm.actions.search.SearchCompiler.Match;
import org.openstreetmap.josm.actions.search.SearchCompiler.ParseError;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.plugins.tracer.TracerModule;
import org.openstreetmap.josm.plugins.tracer.TracerRecord;
import org.openstreetmap.josm.plugins.tracer.connectways.*;

// import org.openstreetmap.josm.plugins.tracer.modules.lpis.LpisRecord;

import static org.openstreetmap.josm.tools.I18n.*;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Pair;

public final class LpisModule extends TracerModule  {

    private boolean moduleEnabled;

    private static final double oversizeInDataBoundsMeters = 5.0;
    private static final GeomDeviation m_connectTolerance = new GeomDeviation(0.2, Math.PI / 3);

    private static final String source = "lpis";
    private static final String lpisUrl = "http://eagri.cz/public/app/wms/plpis_wfs.fcgi";
    private static final String reuseExistingLanduseNodePattern =
        "((landuse=* -landuse=no -landuse=military) | natural=scrub | natural=wood | natural=grassland | natural=wood | leisure=garden)";
    private static final String retraceAreaPattern =
        "(landuse=farmland | landuse=meadow | landuse=orchard | landuse=vineyard | landuse=plant_nursery | (landuse=forest source=lpis))";

    private static final Match m_reuseExistingLanduseNodeMatch;
    private static final Match m_clipLanduseWayMatch;
    private static final Match m_mergeLanduseWayMatch;
    private static final Match m_retraceAreaMatch;

    static {
        try {
            m_reuseExistingLanduseNodeMatch = SearchCompiler.compile(reuseExistingLanduseNodePattern, false, false);
            m_clipLanduseWayMatch = m_reuseExistingLanduseNodeMatch; // use the same
            m_mergeLanduseWayMatch = m_clipLanduseWayMatch; // use the same
            m_retraceAreaMatch = SearchCompiler.compile(retraceAreaPattern, false, false);
        }
        catch (ParseError e) {
            throw new AssertionError(tr("Unable to compile pattern"));
        }
    }

    public LpisModule(boolean enabled) {
        moduleEnabled = enabled;
    }

    @Override
    public void init() {
    }

    @Override
    public Cursor getCursor() {
        return ImageProvider.getCursor("crosshair", "tracer-lpis-sml");
    }

    @Override
    public String getName() {
        return tr("LPIS");
    }

    @Override
    public boolean moduleIsEnabled() {
        return moduleEnabled;
    }

    @Override
    public void setModuleIsEnabled(boolean enabled) {
        moduleEnabled = enabled;
    }

    @Override
    public PleaseWaitRunnable trace(final LatLon pos, final boolean ctrl, final boolean alt, final boolean shift) {
        return new LpisTracerTask (pos, ctrl, alt, shift);
    }

    class ReuseLanduseNearNodes implements IReuseNearNodePredicate {

        // distance tolerancies are in meters
        private final double m_reuseNearNodesToleranceDefault = m_connectTolerance.distanceMeters();
        private final double m_reuseNearNodesToleranceRetracedNodes = 0.40;

        private final double m_lookupDistanceMeters;

        private final Set<EdNode> m_retracedNodes;

        ReuseLanduseNearNodes (Set<EdNode> retraced_nodes) {
            m_retracedNodes = retraced_nodes;
            m_lookupDistanceMeters = Math.max(m_reuseNearNodesToleranceDefault, m_reuseNearNodesToleranceRetracedNodes);
        }

        @Override
        public ReuseNearNodeMethod reuseNearNode(EdNode node, EdNode near_node, double distance_meters) {

            boolean retraced = m_retracedNodes != null && m_retracedNodes.contains(near_node);

            // be more tolerant for untagged nodes occurring in retraced ways, feel free to move them
            if (retraced) {
                System.out.println("RNN: retraced, dist=" + Double.toString(distance_meters));
                if (distance_meters <= m_reuseNearNodesToleranceRetracedNodes)
                    if (!near_node.isTagged())
                        return ReuseNearNodeMethod.moveAndReuseNode;
            }

            // use default tolerance for others, don't move them, just reuse
            System.out.println("RNN: default, dist=" + Double.toString(distance_meters));
            if (distance_meters <= m_reuseNearNodesToleranceDefault)
                return ReuseNearNodeMethod.reuseNode;

            return ReuseNearNodeMethod.dontReuseNode;
        }

        @Override
        public double lookupDistanceMeters() {
            return m_lookupDistanceMeters;
        }
    }

    class LpisTracerTask extends AbstractTracerTask {

        private final ClipAreasSettings m_clipSettings = new ClipAreasSettings (m_connectTolerance);

        LpisTracerTask (LatLon pos, boolean ctrl, boolean alt, boolean shift) {
            super (pos, ctrl, alt, shift);
        }

        private LpisRecord record() {
            return (LpisRecord) super.getRecord();
        }

        @Override
        protected TracerRecord downloadRecord(LatLon pos) throws Exception {
            LpisServer server = new LpisServer();
            return server.getElementData(pos, lpisUrl);
        }

        @Override
        protected EdObject createTracedPolygonImpl(WayEditor editor) {

            System.out.println("  LPIS ID: " + record().getLpisID());
            System.out.println("  LPIS usage: " + record().getUsage());

            // Look for object to retrace
            EdObject retrace_object = null;
            if (m_performRetrace) {
                Pair<EdObject, Boolean> repl = getObjectToRetrace(editor, m_pos);
                retrace_object = repl.a;
                boolean ambiguous_retrace = repl.b;

                if (ambiguous_retrace) {
                    postTraceNotifications().add(tr("Multiple existing LPIS polygons found, retrace is not possible."));
                    return null;
                }
            }

            // Create traced object
            Pair<EdWay, EdMultipolygon> trobj = this.createTracedEdObject(editor);
            if (trobj == null)
                return null;
            EdWay outer_way = trobj.a;
            EdMultipolygon multipolygon = trobj.b;

            // Everything is inside DataSource bounds?
            if (!checkInsideDataSourceBounds(multipolygon == null ? outer_way : multipolygon, retrace_object)) {
                wayIsOutsideDownloadedAreaDialog();
                return null;
            }

            // Connect nodes to near landuse nodes
            // (must be done before retrace updates, we want to use as much old nodes as possible)
            if (!m_performClipping) {
                reuseExistingNodes(multipolygon == null ? outer_way : multipolygon);
            }
            else {
                reuseNearNodes(multipolygon == null ? outer_way : multipolygon, retrace_object);
            }

            // Retrace simple ways - just use the old way
            if (retrace_object != null) {
                if ((multipolygon != null) || !(retrace_object instanceof EdWay) || retrace_object.hasReferrers()) {
                    postTraceNotifications().add(tr("Multipolygon retrace is not supported yet."));
                    return null;
                }
                EdWay retrace_way = (EdWay)retrace_object;
                retrace_way.setNodes(outer_way.getNodes());
                outer_way = retrace_way;
            }

            // Tag object
            tagTracedObject(multipolygon == null ? outer_way : multipolygon);

            // Connect to touching nodes of near landuse polygons
            connectExistingTouchingNodes(multipolygon == null ? outer_way : multipolygon);

            // Clip other areas
            if (m_performClipping) {
                // #### Now, it clips using only the outer way. Consider if multipolygon clip is necessary/useful.
                AreaPredicate filter = new AreaPredicate (m_clipLanduseWayMatch);
                ClipAreas clip = new ClipAreas(editor, m_clipSettings, postTraceNotifications());
                clip.clipAreas(outer_way, filter);
            }

            // Merge duplicate ways
            // (Note that outer way can be merged to another way too, so we must watch it.
            // Otherwise, outer_way variable would refer to an unused way.)
            if (m_performWayMerging) {
                AreaPredicate merge_filter = new AreaPredicate (m_mergeLanduseWayMatch);
                MergeIdenticalWays merger = new MergeIdenticalWays(editor, merge_filter);
                outer_way = merger.mergeWays(editor.getModifiedWays(), true, outer_way);
            }

            return multipolygon == null ? outer_way : multipolygon;
        }

        private void tagTracedObject (EdObject obj) {

            Map <String, String> map = obj.getKeys();

            Map <String, String> new_keys = new HashMap <> (record().getUsageOsm());
            for (Map.Entry<String, String> new_key: new_keys.entrySet()) {
                map.put(new_key.getKey(), new_key.getValue());
            }

            // #### delete any existing retraced tags??

            map.put("source", source);
            map.put("ref", Long.toString(record().getLpisID()));
            obj.setKeys(map);
        }

        private Pair<EdWay, EdMultipolygon> createTracedEdObject (WayEditor editor) {

            // Prepare outer way nodes
            List<LatLon> outer_lls = record().getOuter();
            List<EdNode> outer_nodes = new ArrayList<> (outer_lls.size());
            for (int i = 0; i < outer_lls.size() - 1; i++) {
                EdNode node = editor.newNode(outer_lls.get(i));
                outer_nodes.add(node);
            }
            if (outer_nodes.size() < 3)
                throw new AssertionError(tr("Outer way consists of less than 3 nodes"));

            // Close & create outer way
            outer_nodes.add(outer_nodes.get(0));
            EdWay outer_way = editor.newWay(outer_nodes);

            // Simple way?
            if (!record().hasInners())
                return new Pair<>(outer_way, null);

            // Create multipolygon
            EdMultipolygon multipolygon = editor.newMultipolygon();
            multipolygon.addOuterWay(outer_way);

            for (List<LatLon> inner_lls: record().getInners()) {
                List<EdNode> inner_nodes = new ArrayList<>(inner_lls.size());
                for (int i = 0; i < inner_lls.size() - 1; i++) {
                    inner_nodes.add(editor.newNode(inner_lls.get(i)));
                }

                // Close & create inner way
                if (inner_nodes.size() < 3)
                    throw new AssertionError(tr("Inner way consists of less than 3 nodes"));
                inner_nodes.add(inner_nodes.get(0));
                EdWay way = editor.newWay(inner_nodes);
                multipolygon.addInnerWay(way);
            }

            return new Pair<>(outer_way, multipolygon);
        }

        private Pair<EdObject, Boolean> getObjectToRetrace(WayEditor editor, LatLon pos) {
            AreaPredicate filter = new AreaPredicate(m_retraceAreaMatch);
            Set<EdObject> areas = editor.useNonEditedAreasContainingPoint(pos, filter);

            String lpisref = Long.toString(record().getLpisID());

            // restrict to LPIS areas only, yet ... #### improve in the future
            boolean multiple_areas = false;
            EdObject lpis_area = null;
            for (EdObject area: areas) {
                String source = area.get("source");
                if (source == null || !source.equals("lpis"))
                    continue;

                if (area instanceof EdWay)
                    System.out.println("Retrace candidate EdWay: " + Long.toString(area.getUniqueId()));
                else if (area instanceof EdMultipolygon)
                    System.out.println("Retrace candidate EdMultipolygon: " + Long.toString(area.getUniqueId()));

                String ref = area.get("ref");
                if (ref != null && ref.equals(lpisref)) // exact match ;)
                    return new Pair<>(area, false);

                if (lpis_area == null)
                    lpis_area = area;
                else
                    multiple_areas = true;
            }

            if (multiple_areas) {
                return new Pair<>(null, true);
            }

            if (lpis_area != null) {
                return new Pair<>(lpis_area, false);
            }

            return new Pair<>(null, false);
        }

        private void connectExistingTouchingNodes(EdObject obj) {
            // Setup filters - include landuse nodes only, exclude all nodes of the object itself
            IEdNodePredicate landuse_filter = new AreaBoundaryWayNodePredicate(m_reuseExistingLanduseNodeMatch);
            IEdNodePredicate exclude_my_nodes = new ExcludeEdNodesPredicate(obj);
            IEdNodePredicate filter = new EdNodeLogicalAndPredicate (exclude_my_nodes, landuse_filter);

            obj.connectExistingTouchingNodes(m_connectTolerance, filter);
        }

        private void reuseExistingNodes(EdObject obj) {
            obj.reuseExistingNodes (reuseExistingNodesFilter(obj));
        }

        private void reuseNearNodes(EdObject obj, EdObject retrace_object) {
            Set<EdNode> retrace_nodes = null;
            if (retrace_object != null)
                retrace_nodes = retrace_object.getAllNodes();
            obj.reuseNearNodes (new ReuseLanduseNearNodes(retrace_nodes), reuseExistingNodesFilter(obj));
        }

        private IEdNodePredicate reuseExistingNodesFilter(EdObject obj) {
            // Setup filters - include landuse nodes only, exclude all nodes of the object itself
            IEdNodePredicate nodes_filter = new AreaBoundaryWayNodePredicate(m_reuseExistingLanduseNodeMatch);
            IEdNodePredicate exclude_my_nodes = new ExcludeEdNodesPredicate(obj);
            return new EdNodeLogicalAndPredicate (exclude_my_nodes, nodes_filter);
        }

        private boolean checkInsideDataSourceBounds(EdObject new_object, EdObject retrace_object) {
            LatLonSize bounds_oversize = LatLonSize.get(new_object.getBBox(), oversizeInDataBoundsMeters);
            if (retrace_object != null && !retrace_object.isInsideDataSourceBounds(bounds_oversize))
                return false;
            return new_object.isInsideDataSourceBounds(bounds_oversize);
        }
    }
}

