package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.movement.JumpArc;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.scheduler.PluginTask;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Particle rendering of calculated paths and of a running path search for one player.
 * <ul>
 *     <li><b>Paths</b> are pre-computed once into flat arrays (coloured by {@link MoveType}, gap jumps drawn as their real {@link JumpArc}), redrawn every
 *     {@link #REDRAW_TICKS} ticks and only within {@link #VIEW_DISTANCE} blocks of the player, with a cap per redraw. A pulse that runs along the path shows
 *     the walking direction.</li>
 *     <li><b>Searches</b> ({@link SearchView}) collect the events of the pathfinder in queues (they arrive on the pathfinding thread) and replay them on the
 *     main thread with a particle budget per tick.</li>
 * </ul>
 * Everything is only sent to the owning player. Must be used from the main thread, except the {@link PathVisualizer} callbacks of {@link SearchView}.
 */
public final class PathRenderer
{
    private static final int REDRAW_TICKS = 3;
    private static final float PATH_DUST_SIZE = 1.0f;
    private static final float PATH_MARKER_SIZE = 1.6f;
    private static final double VIEW_DISTANCE = 48.0;
    private static final double VIEW_DISTANCE_SQ = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final int MAX_DOTS_PER_REDRAW = 700;

    private static final Particle.DustOptions NODE = new Particle.DustOptions(Color.AQUA, PATH_DUST_SIZE);
    private static final Particle.DustOptions PULSE = new Particle.DustOptions(Color.WHITE, 1.5f);

    private final UUID uuid;
    private final Map<Path, Shape> shapes = new ConcurrentHashMap<>();
    private final Location scratch = new Location(null, 0, 0, 0);
    private volatile @Nullable PluginTask task;
    private int tick;

    /**
     * @param uuid the player that sees everything this renderer draws
     */
    public PathRenderer(@NotNull UUID uuid)
    {
        this.uuid = uuid;
    }

    /**
     * Colour of a move type, shared by the path and the search display.
     */
    public static @NotNull Color colorOf(@NotNull MoveType type)
    {
        return switch(type)
        {
            case WALK -> Color.LIME;
            case JUMP -> Color.fromRGB(255, 220, 0);
            case FALL -> Color.fromRGB(255, 130, 0);
            case GAP_JUMP -> Color.fromRGB(255, 0, 255);
            case CLIMB -> Color.WHITE;
            case SWIM -> Color.fromRGB(40, 120, 255);
            case FLY -> Color.fromRGB(0, 255, 255);
            case CROUCH -> Color.fromRGB(160, 80, 255);
        };
    }

    private static @NotNull Color gradient(float progress)
    {
        int r;
        int g;
        if(progress < 0.5f)
        {
            r = (int) (progress * 2 * 255);
            g = 255;
        }
        else
        {
            r = 255;
            g = (int) ((1.0f - progress) * 2 * 255);
        }
        return Color.fromRGB(Math.clamp(r, 0, 255), Math.clamp(g, 0, 255), 0);
    }

    private static void spawn(@NotNull Player player, @NotNull Location scratch, @NotNull World world, double x, double y, double z,
                              @NotNull Particle.DustOptions options)
    {
        scratch.setWorld(world);
        scratch.setX(x);
        scratch.setY(y);
        scratch.setZ(z);
        player.spawnParticle(Particle.DUST, scratch, 1, 0, 0, 0, 0, options, true);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------
    // paths
    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * @param path the path
     * @return true if the path is currently drawn
     */
    public boolean isShown(@NotNull Path path)
    {
        return shapes.containsKey(path);
    }

    /**
     * Starts drawing a path.
     *
     * @param path the path
     * @param arc  the jump arc of the entity the path was calculated for (gap jumps are drawn with it)
     */
    public void show(@NotNull Path path, @NotNull JumpArc arc)
    {
        List<Location> points = path.asLocations();
        if(points.isEmpty() || points.getFirst().getWorld() == null)
            return;

        shapes.put(path, Shape.build(points, path.getMoves(), arc));
        ensureTask();
    }

    /**
     * Stops drawing a path.
     *
     * @param path the path
     * @return true if it was drawn
     */
    public boolean hide(@NotNull Path path)
    {
        boolean removed = shapes.remove(path) != null;
        if(shapes.isEmpty())
            cancelTask();
        return removed;
    }

    /**
     * Stops drawing all paths.
     *
     * @return how many paths were drawn
     */
    public int hideAll()
    {
        int count = shapes.size();
        shapes.clear();
        cancelTask();
        return count;
    }

    private synchronized void ensureTask()
    {
        PluginTask current = task;
        if(current != null && !current.isCancelled())
            return;

        Player player = Bukkit.getPlayer(uuid);
        if(player == null || !player.isOnline())
            return;

        task = SchedulerProvider.get().runTimerForEntity(player, this::renderTick, 1L, 1L);
    }

    private synchronized void cancelTask()
    {
        PluginTask current = task;
        if(current != null && !current.isCancelled())
            current.cancel();
        task = null;
    }

    private void renderTick()
    {
        Player player = Bukkit.getPlayer(uuid);
        if(player == null || !player.isOnline())
        {
            cancelTask();
            return;
        }

        boolean redraw = ++tick % REDRAW_TICKS == 0;
        Location eye = player.getLocation();
        for(Shape shape : shapes.values())
        {
            if(!shape.world.equals(eye.getWorld()))
                continue;

            if(redraw)
                shape.draw(player, scratch, eye.getX(), eye.getY(), eye.getZ());
            shape.pulse(player, scratch, eye.getX(), eye.getY(), eye.getZ());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------
    // path shape
    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Starts a display for a running search (visited blocks and search front; special moves are not shown). Pass it to the pathfinder as {@link PathVisualizer}
     * and call {@link SearchView#finish()} when the search is over.
     *
     * @param arc the jump arc of the searching entity (gap jumps are drawn with it)
     * @return the search view
     */
    public @NotNull SearchView startSearch(@NotNull JumpArc arc)
    {
        return startSearch(arc, false);
    }

    /**
     * Starts a display for a running search.
     *
     * @param arc       the jump arc of the searching entity (gap jumps are drawn with it)
     * @param showMoves also draw the special moves (jump, fall, gap jump, climb, swim) the planner considered; a lot of particles, meant for debugging the move
     *                  generator
     * @return the search view
     */
    public @NotNull SearchView startSearch(@NotNull JumpArc arc, boolean showMoves)
    {
        return startSearch(arc, showMoves, SearchStyle.DUST);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Starts a display for a running search.
     *
     * @param arc       the jump arc of the searching entity (gap jumps are drawn with it)
     * @param showMoves also draw the special moves the planner considered (a lot of particles, meant for debugging the move generator)
     * @param style     how the visited blocks are drawn
     * @return the search view
     */
    public @NotNull SearchView startSearch(@NotNull JumpArc arc, boolean showMoves, @NotNull SearchStyle style)
    {
        SearchView view = new SearchView(arc, showMoves, style);
        view.start();
        return view;
    }

    /**
     * How the blocks visited by a search are drawn.
     */
    public enum SearchStyle
    {
        /**
         * Coloured dust (green to red in the order of the search). Dust is short-lived, so every dot is redrawn a couple of times to stay visible for about one
         * to two seconds.
         */
        DUST,

        /**
         * Flames (soul flames for the search front). They live much longer than dust, but have no colour gradient.
         */
        FLAME
    }

    private static final class Dots
    {
        double[] x = new double[256];
        double[] y = new double[256];
        double[] z = new double[256];
        Particle.DustOptions[] options = new Particle.DustOptions[256];
        int size;

        void add(double px, double py, double pz, @NotNull Particle.DustOptions option)
        {
            if(size == x.length)
            {
                int capacity = size * 2;
                x = Arrays.copyOf(x, capacity);
                y = Arrays.copyOf(y, capacity);
                z = Arrays.copyOf(z, capacity);
                options = Arrays.copyOf(options, capacity);
            }
            x[size] = px;
            y[size] = py;
            z[size] = pz;
            options[size++] = option;
        }
    }

    private static final class Shape
    {
        final World world;
        final double[] x;
        final double[] y;
        final double[] z;
        final Particle.DustOptions[] options;
        final int size;
        int head;

        private Shape(@NotNull World world, @NotNull Dots dots)
        {
            this.world = world;
            this.size = dots.size;
            this.x = Arrays.copyOf(dots.x, size);
            this.y = Arrays.copyOf(dots.y, size);
            this.z = Arrays.copyOf(dots.z, size);
            this.options = Arrays.copyOf(dots.options, size);
        }

        static @NotNull Shape build(@NotNull List<Location> points, @NotNull List<MoveType> moves, @NotNull JumpArc arc)
        {
            boolean typed = moves.size() == points.size();
            int n = points.size();
            Dots dots = new Dots();

            for(int i = 0; i < n; i++)
            {
                Location current = points.get(i);
                dots.add(current.getX(), current.getY() + 0.2, current.getZ(), NODE);
                if(i + 1 >= n)
                    break;

                MoveType type = typed ? moves.get(i + 1) : MoveType.WALK;
                float from = (float) i / (n - 1);
                float to = (float) (i + 1) / (n - 1);
                addSegment(dots, current, points.get(i + 1), type, from, to, arc);
            }
            return new Shape(points.getFirst().getWorld(), dots);
        }

        private static void addSegment(@NotNull Dots dots, @NotNull Location a, @NotNull Location b, @NotNull MoveType type, float from, float to,
                                       @NotNull JumpArc arc)
        {
            double ax = a.getX();
            double ay = a.getY() + 0.2;
            double az = a.getZ();
            double dx = b.getX() - ax;
            double dy = b.getY() - a.getY();
            double dz = b.getZ() - az;

            Particle.DustOptions marker = new Particle.DustOptions(colorOf(type), PATH_MARKER_SIZE);

            if(type == MoveType.GAP_JUMP)
            {
                int ticks = arc.ticksTo(dy);
                if(ticks >= 2)
                {
                    Particle.DustOptions option = new Particle.DustOptions(colorOf(type), PATH_DUST_SIZE);
                    dots.add(ax, ay, az, marker);
                    for(int k = 1; k < ticks; k++)
                    {
                        double f = (double) k / ticks;
                        dots.add(ax + dx * f, a.getY() + arc.heightOf(k) + 0.2, az + dz * f, option);
                    }
                    return;
                }
            }

            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if(dist < 1.0E-6)
                return;

            if(type != MoveType.WALK)
                dots.add(ax, ay, az, marker);

            double spacing = type == MoveType.WALK ? 0.5 : 0.35;
            double clearance = Math.min(0.25, dist / 2.0);
            for(double d = clearance; d <= dist - clearance + 1.0E-9; d += spacing)
            {
                double f = d / dist;
                Color color = type == MoveType.WALK ? gradient(from + (to - from) * (float) f) : colorOf(type);
                dots.add(ax + dx * f, ay + dy * f, az + dz * f, new Particle.DustOptions(color, PATH_DUST_SIZE));
            }
        }

        void draw(@NotNull Player player, @NotNull Location scratch, double px, double py, double pz)
        {
            int spawned = 0;
            for(int i = 0; i < size && spawned < MAX_DOTS_PER_REDRAW; i++)
            {
                double dx = x[i] - px;
                double dy = y[i] - py;
                double dz = z[i] - pz;
                if(dx * dx + dy * dy + dz * dz > VIEW_DISTANCE_SQ)
                    continue;

                spawn(player, scratch, world, x[i], y[i], z[i], options[i]);
                spawned++;
            }
        }

        void pulse(@NotNull Player player, @NotNull Location scratch, double px, double py, double pz)
        {
            if(size == 0)
                return;

            head = (head + 2) % size;
            for(int k = 0; k < 2; k++)
            {
                int i = (head + k) % size;
                double dx = x[i] - px;
                double dy = y[i] - py;
                double dz = z[i] - pz;
                if(dx * dx + dy * dy + dz * dz <= VIEW_DISTANCE_SQ)
                    spawn(player, scratch, world, x[i], y[i] + 0.1, z[i], PULSE);
            }
        }
    }

    private record Segment(@NotNull Location from, @NotNull Location to, @NotNull MoveType type)
    {
    }

    /**
     * Replays the events of a path search close to real time: expanded blocks as small dust coloured by search order and the search front as large white dust.
     * The display never falls behind: the visited blocks are thinned out as the search grows (the first 1000 are all shown, later only every n-th), the
     * particle budget adapts to the backlog, and when the search is over the rest is dropped after a few ticks. Optionally the special moves are drawn too. The
     * callbacks may run on any thread.
     */
    public final class SearchView implements PathVisualizer
    {
        private static final int BASE_BUDGET = 150;
        private static final int SAMPLE_BASE = 1000;
        private static final int MAX_SEGMENTS = 300;
        private static final int LINGER_TICKS = 8;
        private static final float VISITED_SIZE = 1.2f;
        private static final int REFRESH_INTERVAL = 7;
        private static final int REFRESHES = 2;
        private static final int REFRESH_BUDGET = 250;

        private final Queue<Location> visited = new ConcurrentLinkedQueue<>();
        private final Queue<Location> frontier = new ConcurrentLinkedQueue<>();
        private final Queue<Segment> moves = new ConcurrentLinkedQueue<>();
        private final AtomicInteger seen = new AtomicInteger();
        private final AtomicInteger visitedCount = new AtomicInteger();
        private final AtomicInteger moveCount = new AtomicInteger();
        private final JumpArc arc;
        private final boolean showMoves;
        private final SearchStyle style;
        private final ArrayDeque<Refresh> refresh = new ArrayDeque<>();
        private final Location scratch = new Location(null, 0, 0, 0);
        private int tickCount;

        private volatile boolean finished;
        private volatile @Nullable PluginTask task;
        private int shown;
        private int lingerTicks;

        private SearchView(@NotNull JumpArc arc, boolean showMoves, @NotNull SearchStyle style)
        {
            this.arc = arc;
            this.showMoves = showMoves;
            this.style = style;
        }

        private void start()
        {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null || !player.isOnline())
            {
                finished = true;
                return;
            }

            task = SchedulerProvider.get().runTimerForEntity(player, this::tick, 1L, 1L);
        }

        /**
         * Tells the view that the search is over (successful, failed or cancelled). It keeps drawing for a few more ticks and then stops itself; whatever is
         * still queued by then is dropped.
         */
        public void finish()
        {
            finished = true;
        }

        /**
         * Stops the display immediately and drops everything that is still queued.
         */
        public void cancel()
        {
            finished = true;
            PluginTask current = task;
            if(current != null && !current.isCancelled())
                current.cancel();
            task = null;
            visited.clear();
            frontier.clear();
            moves.clear();
            visitedCount.set(0);
            moveCount.set(0);
        }

        @Override
        public void visited(@NotNull Location location)
        {
            int n = seen.incrementAndGet();
            int stride = Math.max(1, n / SAMPLE_BASE);
            if(n % stride != 0)
                return;

            visited.add(location);
            visitedCount.incrementAndGet();
        }

        @Override
        public void frontier(@NotNull Location location)
        {
            frontier.add(location);
        }

        @Override
        public void move(@NotNull Location from, @NotNull Location to, @NotNull MoveType type)
        {
            if(!showMoves || moveCount.get() >= MAX_SEGMENTS)
                return;

            moves.add(new Segment(from, to, type));
            moveCount.incrementAndGet();
        }

        private void stop()
        {
            PluginTask current = task;
            if(current != null && !current.isCancelled())
                current.cancel();
            task = null;
            visited.clear();
            frontier.clear();
            moves.clear();
            refresh.clear();
            visitedCount.set(0);
            moveCount.set(0);
        }

        private void tick()
        {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null || !player.isOnline())
            {
                stop();
                return;
            }

            int budget = Math.max(BASE_BUDGET, visitedCount.get() / 6);

            tickCount++;
            drawRefreshes(player);

            Location l;
            while(budget > 0 && (l = frontier.poll()) != null)
            {
                if(style == SearchStyle.FLAME)
                    spawnParticle(player, Particle.SOUL_FIRE_FLAME, l.getWorld(), l.getX(), l.getY() + 0.2, l.getZ());
                else
                    drawDust(player, l.getWorld(), l.getX(), l.getY() + 0.2, l.getZ(), new Particle.DustOptions(Color.WHITE, 1.6f));
                budget -= 2;
            }

            Segment s;
            int segments = 0;
            while(budget > 0 && segments++ < 20 && (s = moves.poll()) != null)
            {
                moveCount.decrementAndGet();
                budget -= drawMove(player, s);
            }

            while(budget > 0 && (l = visited.poll()) != null)
            {
                visitedCount.decrementAndGet();
                if(style == SearchStyle.FLAME)
                    spawnParticle(player, Particle.FLAME, l.getWorld(), l.getX(), l.getY() + 0.1, l.getZ());
                else
                {
                    Color color = gradient(Math.min(1f, shown++ / 1500f));
                    drawDust(player, l.getWorld(), l.getX(), l.getY() + 0.1, l.getZ(), new Particle.DustOptions(color, VISITED_SIZE));
                }
                budget--;
            }

            if(finished && (visited.isEmpty() || ++lingerTicks >= LINGER_TICKS))
            {
                visited.clear();
                frontier.clear();
                moves.clear();
                visitedCount.set(0);
                moveCount.set(0);
                if(refresh.isEmpty())
                    stop();
            }
        }

        /**
         * Draws a dust dot now and schedules its redraws.
         */
        private void drawDust(@NotNull Player player, @NotNull World world, double x, double y, double z, @NotNull Particle.DustOptions options)
        {
            spawn(player, scratch, world, x, y, z, options);
            refresh.add(new Refresh(world, x, y, z, options, tickCount + REFRESH_INTERVAL, REFRESHES));
        }

        private void drawRefreshes(@NotNull Player player)
        {
            int budget = REFRESH_BUDGET;
            Refresh r;
            while(budget-- > 0 && (r = refresh.peek()) != null && r.due() <= tickCount)
            {
                refresh.poll();
                spawn(player, scratch, r.world(), r.x(), r.y(), r.z(), r.options());
                if(r.left() > 1)
                    refresh.add(new Refresh(r.world(), r.x(), r.y(), r.z(), r.options(), tickCount + REFRESH_INTERVAL, r.left() - 1));
            }
        }

        private void spawnParticle(@NotNull Player player, @NotNull Particle particle, @NotNull World world, double x, double y, double z)
        {
            scratch.setWorld(world);
            scratch.setX(x);
            scratch.setY(y);
            scratch.setZ(z);
            player.spawnParticle(particle, scratch, 1, 0, 0, 0, 0, null, true);
        }

        private int drawMove(@NotNull Player player, @NotNull Segment s)
        {
            World world = s.to().getWorld();
            Particle.DustOptions option = new Particle.DustOptions(colorOf(s.type()), 0.8f);
            double ax = s.from().getX();
            double ay = s.from().getY();
            double az = s.from().getZ();
            double dx = s.to().getX() - ax;
            double dy = s.to().getY() - ay;
            double dz = s.to().getZ() - az;

            int ticks = s.type() == MoveType.GAP_JUMP ? arc.ticksTo(dy) : -1;
            if(ticks >= 2)
            {
                for(int k = 1; k < ticks; k++)
                {
                    double f = (double) k / ticks;
                    spawn(player, scratch, world, ax + dx * f, ay + arc.heightOf(k) + 0.2, az + dz * f, option);
                }
                return ticks;
            }

            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int points = Math.clamp((int) Math.ceil(dist / 0.3), 2, 16);
            for(int k = 0; k <= points; k++)
            {
                double f = (double) k / points;
                spawn(player, scratch, world, ax + dx * f, ay + dy * f + 0.2, az + dz * f, option);
            }
            return points;
        }

        /**
         * A dust dot that is drawn again after a while because dust disappears after a few ticks.
         */
        private record Refresh(@NotNull World world, double x, double y, double z, @NotNull Particle.DustOptions options, int due, int left)
        {
        }
    }
}