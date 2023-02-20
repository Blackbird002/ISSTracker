package org.blackbird;

import gov.nasa.worldwind.BasicModel;
import gov.nasa.worldwind.Model;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.awt.WorldWindowGLJPanel;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.ShapeAttributes;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;
import org.json.JSONException;
import org.json.JSONObject;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * When instanciated, creates a Swing JFrame with a centered Panel containing WorldWind.
 * The rendered "pushpin" {@link gov.nasa.worldwind.render.PointPlacemark} represents the
 * last known Position of the ISS.
 */
public class ISSTracker
{
    private final WorldWindowGLJPanel wwPanel;

    /**
     * Stores all Positions we have collected so far
     */
    private final List<Position> issPositions = new ArrayList<>();

    /**
     * This represents the renderable ground track
     */
    private final Path groundTrack;

    /**
     * The main layer we're using right now
     */
    private final RenderableLayer issLayer;

    /**
     * The "pushpin"
     */
    private final PointPlacemark issMarker;

    private Position issPosition;

    /**
     * The maximum # of path positions to store in issPositions (ArrayList)
     */
    private final int MAXPOSITIONS = 500;

    /**
     * The ISS Position update period (ms) - the period of time we wait to get the next ISS Position
     */
    private final int UPDATEPERIOD = 15000;

    private String lastPositionTimestampStr = "";


    /**
     * Configure the path to draw its outline and position points in the colors below. We use three colors that
     * are evenly distributed along the path's length and gradually increasing in opacity. Position colors may
     * be assigned in any manner the application chooses.
     */
    Color[] groundTrackPathColors =
    {
        new Color(1f, 0f, 0f, 0.5f), // Red
        new Color(1f, 1f, 0f, 0.5f), // Green
        new Color(0f, 1f, 0f, 0.5f), // Blue
    };

    /**
     * Constructor
     */
    public ISSTracker()
    {
        Dimension appResolution = new Dimension(1280,720);

        JFrame frame = new JFrame("ISS Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setPreferredSize(appResolution);
        frame.setJMenuBar(createMainMenuBar());

        wwPanel = new WorldWindowGLJPanel();

        // Create a new layer for the ISS PointPlacemark and Path
        issLayer = new RenderableLayer();

        // Add the ISS layer to the WorldWindowGLCanvas
        Model m = new BasicModel();
        m.getLayers().add(issLayer);
        wwPanel.setModel(m);

        // Create a new Position object with the current latitude and longitude of the ISS
        issPosition = getCurrentIssPosition();

        // Create a new PointPlacemark at the position of the ISS we got
        issMarker = new PointPlacemark(issPosition);

        // Use the altitude data (elevation) to approximate the ISS altitude visually
        issMarker.setAltitudeMode(0);

        // Draw the nadir line from the pushpin
        issMarker.setLineEnabled(true);

        // Set the pushpin's label
        issMarker.setLabelText(constructIssMarkerLabel(issPosition));

        // Add the ISS PointPlacemark to the ISS layer
        issLayer.addRenderable(issMarker);

        // Set the eye position of the WorldWindowGLCanvas to the position of the ISS "look at"
        wwPanel.getView().setEyePosition(issPosition);

        // Add the WorldWind canvas to the center of the JFrame
        frame.getContentPane().add(wwPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);

        // Create a path to display the ground track
        groundTrack = new Path(issPositions);
        groundTrack.setFollowTerrain(true);

        // To use "elevation" data as the altitude, set relative to ground
        groundTrack.setAltitudeMode(WorldWind.RELATIVE_TO_GROUND);

        groundTrack.setPathType(AVKey.GREAT_CIRCLE);

        // Shows the previous positions (when zoomed in)
        groundTrack.setShowPositions(true);
        groundTrack.setShowPositionsScale(2);

        // Add the initial ISS position
        issPositions.add(issPosition);
        groundTrack.setPositions(issPositions);

        // Create and set an attribute bundle. Specify only the path's outline width; the position colors override
        // the outline color and opacity.
        ShapeAttributes attrs = new BasicShapeAttributes();
        attrs.setOutlineWidth(8);
        attrs.setEnableLighting(true);
        groundTrack.setAttributes(attrs);

        // Add the ground track to the ISS layer
        issLayer.addRenderable(groundTrack);

        TimerTask task = new ISSPositionTask();
        Timer timer = new Timer();

        timer.schedule(task, UPDATEPERIOD, UPDATEPERIOD);

        // Get the current view
        BasicOrbitView view = (BasicOrbitView) wwPanel.getView();

        // Set the camera altitude (meters)
        view.setZoom(8000000.0);
    }

    /**
     * Hits api.wheretheiss.at web API and gets the current ISS LAN, LON, ALT
     * @return Returns the ISS Position
     */
    public static Position getCurrentIssPosition()
    {
        double lat = 0.0;
        double lon = 0.0;
        double alt = 0.0;

        try
        {
            JSONObject json = readJsonFromUrl("https://api.wheretheiss.at/v1/satellites/25544");

            lat = Double.parseDouble(json.get("latitude").toString());
            lon = Double.parseDouble(json.get("longitude").toString());
            alt = Double.parseDouble(json.get("altitude").toString());
        }
        catch (Exception ex)
        {
            System.out.println("There was a problem retrieving current ISS position: " + ex.getMessage());
        }

        // API reports altitude in Km, so multiply it by 1000 (1km == 1000m)
        return Position.fromDegrees(lat, lon, alt * 1000);
    }

    /**
     * TimerTask (thread) that does the following when fired:
     * <ol>
     *     <li>Get current ISS LAT, LON, ALT</li>
     *     <li>Adds the new position to issPositions list</li>
     *     <li>Sets the positions again for groundTrack</li>
     *     <li>Updates issMarker's position and text label</li>
     *     <li>Redraws everything</li>
     *     <li></li>
     * </ol>
     */
    private class ISSPositionTask extends TimerTask
    {
        public void run()
        {
            // Get the current position of the ISS
            issPosition = ISSTracker.getCurrentIssPosition();

            // Update the ground track path
            issPositions.add(issPosition);

            // If we're beyond the EXPOSITIONS threshold, delete the first one in the list (the oldest)
            if (issPositions.size() > MAXPOSITIONS)
            {
                // Remove the first ISS position in issPositions
                issPositions.remove(0);
            }

            // Update the positions
            groundTrack.setPositions(issPositions);
            issMarker.setPosition(issPosition);

            // Remove all the current renderables (we need to add the updated ones)
            issLayer.removeAllRenderables();

            // Update the Path Colors
            groundTrack.setPositionColors(new ExamplePositionColors(groundTrackPathColors, issPositions.size()));

            // Update the issMarker's label
            issMarker.setLabelText(constructIssMarkerLabel(issPosition));

            //Add the renderables to issLayer
            issLayer.addRenderable(issMarker);
            issLayer.addRenderable(groundTrack);

            // Causes a repaint event to be enqueued
            wwPanel.redraw();
        }
    }

    /**
     * Constructs the label associated with the ISS PointPlacemark (the pushpin)
     * @param issPosition Position of the ISS
     * @return Formatted label for the PointPlacemark
     */
    public String constructIssMarkerLabel(Position issPosition)
    {
        SimpleDateFormat formatter = new SimpleDateFormat("MM-dd-yyyy HH:mm:ss");
        Date date = new Date();
        lastPositionTimestampStr =  "[" + formatter.format(date) + "]";

        return String.format("ISS - %s LAT: %s LON: %s ALT: %.3f km",
                lastPositionTimestampStr,
                issPosition.getLatitude(),
                issPosition.getLongitude(),
                issPosition.getElevation() / 1000); // Convert to km
    }

    /**
     * Basic implementation of {@link gov.nasa.worldwind.render.Path.PositionColors} that evenly distributes the
     * specified colors along a path with the specified length. For example, if the Colors array contains red, green,
     * blue (in that order) and the pathLength is 6, this assigns the following colors to each path ordinal: 0:red,
     * 1:red, 2:green, 3:green, 4:blue, 5:blue.
     */
    public static class ExamplePositionColors implements Path.PositionColors
    {
        protected Color[] colors;
        protected int pathLength;

        public ExamplePositionColors(Color[] colors, int pathLength)
        {
            this.colors = colors;
            this.pathLength = pathLength;
        }

        public Color getColor(Position position, int ordinal)
        {
            int index = colors.length * ordinal / this.pathLength;
            return this.colors[index];
        }
    }

    private static String readAllToStr(Reader rd) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1)
        {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    /**
     * Hits given endpoint, gets resopnse as JSON (throws exceptions)
     * @param url Endpoint to hit
     * @return JSONObject (the response)
     * @throws IOException
     * @throws JSONException
     */
    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException
    {
        try (InputStream is = new URL(url).openStream())
        {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String jsonText = readAllToStr(rd);
            return new JSONObject(jsonText);
        }
    }

    /**
     * Creates the Main Swing MenuBar
     * @return Constructed JMenuBar
     */
    public JMenuBar createMainMenuBar()
    {
        //Create the menu bar.
        JMenuBar mainMenuBar = new JMenuBar();

        //Build the File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem exitItem = new JMenuItem("Exit", KeyEvent.VK_E);
        exitItem.getAccessibleContext().setAccessibleDescription("Exit the application");

        // Exit the application if user clicks on Exit
        exitItem.addActionListener(exitAction ->
        {
            System.out.println("Exiting ISSViewer...");
            System.exit(0);
        });

        fileMenu.add(exitItem);
        mainMenuBar.add(fileMenu);

        //Build the Help menu
        JMenu helpMenu = new JMenu("Help");
        fileMenu.setMnemonic(KeyEvent.VK_H);
        JMenuItem aboutItem = new JMenuItem("About", KeyEvent.VK_A);
        helpMenu.add(aboutItem);
        mainMenuBar.add(helpMenu);

        return mainMenuBar;
    }

    public static void main(String[] args)
    {
        //Schedule a job for the event dispatch thread (EDT)
        //Creates and shows application GUI.
        SwingUtilities.invokeLater(ISSTracker::new);
    }
}
