import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.swing.*;

/**
 * ArcheryChallenge.java
 * Clean single-file Swing game (stickman archer).
 *
 * - Fixed ambiguous Timer by using javax.swing.Timer explicitly.
 * - Removed duplicate methods (spawnParticles, loadHighScore, saveHighScore).
 * - Realistic arrow flight, accurate target collision, arrows stick to target.
 */
public class ArcheryChallenge extends JPanel {

    // Base canvas size for scaling
    private static final int BASE_WIDTH = 960;
    private static final int BASE_HEIGHT = 640;

    // Physics tuning
    private static final double BASE_GRAVITY = 950.0;
    private static final double DRAG = 0.03;
    private static final double MIN_SPEED = 260.0;
    private static final double MAX_SPEED = 1050.0;
    private static final double MAX_CHARGE = 1.6; // seconds

    private static final int BASE_ARROW_LEN = 56;
    private static final String HIGHSCORE_FILE = "archery_highscore.txt";

    // Layout that adapts to panel size
    private int W = BASE_WIDTH, H = BASE_HEIGHT;
    private int groundY, archerX, archerY, arrowLength;
    private double gravity;

    // Collections
    private final List<Arrow> arrows = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<TextPopup> popups = new ArrayList<>();
    private final List<PowerUp> powerUps = new ArrayList<>();

    // Target (initialized before using difficulty)
    private final Target target;

    // UI controls
    private final JButton startBtn = new JButton("Start");
    private final JButton pauseBtn = new JButton("Pause");
    private final JButton restartBtn = new JButton("Restart");
    private final JButton quitBtn = new JButton("Quit");
    private final JComboBox<String> difficultyBox = new JComboBox<>(new String[] {"Easy", "Medium", "Hard"});

    // Game state
    private enum State { MENU, PLAYING, PAUSED, GAME_OVER }
    private State state = State.MENU;
    private int score = 0;
    private int arrowsLeft = 14;
    private int timeLeft = 60;
    private int highScore = 0;

    // Input/aiming
    private boolean charging = false;
    private long chargeStartNanos = 0;
    private double aimAngle = -Math.PI/8;
    private int pointerX, pointerY;
    private boolean pointerActive = false;

    // Timing
    private long lastNanos = System.nanoTime();
    private final javax.swing.Timer mainTimer;
    private final javax.swing.Timer secondTimer;
    private final Random rnd = new Random();

    public ArcheryChallenge() {
        setPreferredSize(new Dimension(BASE_WIDTH, BASE_HEIGHT));
        setBackground(new Color(135,206,235));
        setLayout(null);

        recomputeLayout();

        // Initialize target BEFORE applying difficulty
        target = new Target(W - (int)(0.18 * W), groundY - (int)(0.18 * H),
                (int)(0.09 * Math.min(W, H)), -160.0);
        target.setupRings();

        placeControls();

        // Hook UI
        startBtn.addActionListener(e -> startGame());
        pauseBtn.addActionListener(e -> togglePause());
        restartBtn.addActionListener(e -> restartGame());
        quitBtn.addActionListener(e -> System.exit(0));
        difficultyBox.addActionListener(e -> applyDifficulty((String) difficultyBox.getSelectedItem()));
        difficultyBox.setSelectedItem("Medium");

        // Input listeners
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (state != State.PLAYING) return;
                if (isOverUI(e.getX(), e.getY())) return;
                pointerActive = true; pointerX = e.getX(); pointerY = e.getY();
                aimAngle = clampAngle(Math.atan2(pointerY - archerY, pointerX - archerX));
                startCharging();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (state != State.PLAYING) return;
                if (!pointerActive) return;
                pointerActive = false;
                releaseAndFire(e.getX(), e.getY());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (state != State.PLAYING) return;
                if (!pointerActive) return;
                pointerX = e.getX(); pointerY = e.getY();
                aimAngle = clampAngle(Math.atan2(pointerY - archerY, pointerX - archerX));
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (state != State.PLAYING) return;
                if (!pointerActive) { pointerX = e.getX(); pointerY = e.getY(); aimAngle = clampAngle(Math.atan2(pointerY - archerY, pointerX - archerX)); }
            }
        });

        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_P) togglePause();
                if (e.getKeyCode() == KeyEvent.VK_R) restartGame();
                if (e.getKeyCode() == KeyEvent.VK_Q) System.exit(0);
                if (e.getKeyCode() == KeyEvent.VK_SPACE && state == State.PLAYING && !charging && arrowsLeft > 0) {
                    startCharging();
                    releaseAndFire(archerX + (int)(Math.cos(aimAngle)*120), archerY + (int)(Math.sin(aimAngle)*110));
                }
            }
        });

        loadHighScore();

        mainTimer = new javax.swing.Timer(16, e -> mainLoop());
        secondTimer = new javax.swing.Timer(1000, e -> { if (state == State.PLAYING) { timeLeft--; if (timeLeft <= 0) endGame(); } });

        mainTimer.start();
        secondTimer.start();
        lastNanos = System.nanoTime();
    }

    // ---------- Layout & controls ----------
    private void recomputeLayout() {
        Dimension pref = getPreferredSize();
        W = (getWidth() > 0) ? getWidth() : pref.width;
        H = (getHeight() > 0) ? getHeight() : pref.height;
        groundY = (int)(H * 0.82);
        archerX = (int)(W * 0.12);
        archerY = groundY - (int)(H * 0.05);
        arrowLength = (int)(BASE_ARROW_LEN * Math.min(W/(double)BASE_WIDTH, H/(double)BASE_HEIGHT));
        gravity = BASE_GRAVITY * Math.min(W/(double)BASE_WIDTH, H/(double)BASE_HEIGHT);
    }

    private void placeControls() {
        int bx = BASE_WIDTH - 220;
        startBtn.setBounds(bx, 30, 200, 36);
        pauseBtn.setBounds(bx, 76, 200, 36);
        restartBtn.setBounds(bx, 122, 200, 36);
        quitBtn.setBounds(bx, 168, 200, 36);
        difficultyBox.setBounds(bx, 214, 200, 28);
        add(startBtn); add(pauseBtn); add(restartBtn); add(quitBtn); add(difficultyBox);
    }

    private boolean isOverUI(int x, int y) { return x > getWidth() - 240; }

    // ---------- Difficulty & game control ----------
    private void applyDifficulty(String s) {
        if (s == null) s = "Medium";
        switch (s) {
            case "Easy": arrowsLeft = 20; timeLeft = 80; target.radius = (int)(Math.min(W,H)*0.12); target.vx = -120; break;
            case "Hard": arrowsLeft = 10; timeLeft = 45; target.radius = (int)(Math.min(W,H)*0.07); target.vx = -260; break;
            default: arrowsLeft = 14; timeLeft = 60; target.radius = (int)(Math.min(W,H)*0.09); target.vx = -180; break;
        }
        target.setupRings();
    }

    private void startGame() {
        recomputeLayout();
        score = 0; arrows.clear(); particles.clear(); popups.clear(); powerUps.clear();
        applyDifficulty((String) difficultyBox.getSelectedItem());
        state = State.PLAYING; lastNanos = System.nanoTime(); requestFocusInWindow();
    }
    private void restartGame() { startGame(); }
    private void togglePause() { if (state == State.PLAYING) state = State.PAUSED; else if (state == State.PAUSED) { state = State.PLAYING; lastNanos = System.nanoTime(); } repaint(); }
    private void endGame() {
        state = State.GAME_OVER;
        if (score > highScore) { highScore = score; saveHighScore(); }
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "Time's up!\nScore: " + score + "\nHigh Score: " + highScore, "Game Over", JOptionPane.INFORMATION_MESSAGE);
            state = State.MENU; repaint();
        });
    }

    // ---------- Input & firing ----------
    private void startCharging() { if (arrowsLeft <= 0) return; charging = true; chargeStartNanos = System.nanoTime(); }
    private void releaseAndFire(int mx, int my) {
        if (!charging) return;
        charging = false;
        long held = System.nanoTime() - chargeStartNanos;
        double heldSec = Math.min(held / 1_000_000_000.0, MAX_CHARGE);
        double t = heldSec / MAX_CHARGE;
        double eased = Math.sin(t * Math.PI * 0.5);
        double speed = MIN_SPEED + eased * (MAX_SPEED - MIN_SPEED);
        double angle = clampAngle(Math.atan2(my - archerY, mx - archerX));
        double vx = Math.cos(angle) * speed;
        double vy = Math.sin(angle) * speed;
        Arrow a = new Arrow(archerX + Math.cos(angle)*(arrowLength/2 + 10), archerY + Math.sin(angle)*(arrowLength/2 + 10), vx, vy, angle);
        arrows.add(a);
        arrowsLeft = Math.max(0, arrowsLeft - 1);
    }
    private double clampAngle(double a) { double min = -Math.PI/3.0, max = Math.PI/3.0; if (a < min) return min; if (a > max) return max; return a; }

    // ---------- Main loop ----------
    private void mainLoop() {
        recomputeLayout();
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        if (dt <= 0) dt = 0.016;
        lastNanos = now;

        if (state != State.PLAYING) { repaint(); return; }

        // target
        target.update(dt);

        // spawn power-ups occasionally
        if (rnd.nextDouble() < 0.003) {
            double px = W + 40; double py = 140 + rnd.nextInt(Math.max(1, groundY - 200));
            powerUps.add(new PowerUp(px, py));
        }

        // power-ups update
        for (int i = powerUps.size()-1; i >= 0; i--) {
            PowerUp p = powerUps.get(i); p.update(dt);
            if (p.x < -100) powerUps.remove(i);
        }

        // arrows
        for (int i = arrows.size()-1; i >= 0; i--) {
            Arrow a = arrows.get(i);
            if (!a.stuck) {
                a.vy += gravity * dt;
                a.vx -= a.vx * DRAG * dt;
                a.vy -= a.vy * DRAG * dt;
                a.x += a.vx * dt; a.y += a.vy * dt;
                a.angle = Math.atan2(a.vy, a.vx);

                double tipX = a.x + Math.cos(a.angle) * arrowLength/2.0;
                double tipY = a.y + Math.sin(a.angle) * arrowLength/2.0;

                // power-up collision
                for (int j = powerUps.size()-1; j >= 0; j--) {
                    PowerUp pu = powerUps.get(j);
                    if (pu.hit(tipX, tipY)) {
                        pu.apply(this);
                        powerUps.remove(j);
                        spawnParticles(tipX, tipY, Color.CYAN, 12);
                        popups.add(new TextPopup(tipX, tipY, "+Power", 900));
                    }
                }

                // target collision
                if (target.hit(tipX, tipY)) {
                    int pts = target.getPointsForHit(tipX, tipY);
                    score += pts;
                    spawnParticles(tipX, tipY, pts >= 100 ? Color.YELLOW : Color.ORANGE, 20);
                    popups.add(new TextPopup(tipX, tipY, commentaryForPoints(pts), 1100));
                    target.wobble();
                    a.stickTo(target, tipX, tipY);
                    if (pts >= 100) popups.add(new TextPopup(tipX, tipY - 30, "BULLSEYE!", 1400));
                } else {
                    if (a.x < -400 || a.x > W + 400 || a.y > H + 400 || a.y < -400) arrows.remove(i);
                }
            } else {
                a.updateStuck();
            }
        }

        // update particles and popups
        for (int i = particles.size()-1; i >= 0; i--) {
            Particle p = particles.get(i); p.update(dt); if (p.life <= 0) particles.remove(i);
        }
        for (int i = popups.size()-1; i >= 0; i--) {
            TextPopup tp = popups.get(i); tp.life -= dt * 1000; if (tp.life <= 0) popups.remove(i);
        }

        repaint();
    }

    private String commentaryForPoints(int pts) {
        if (pts >= 100) return "Excellent!";
        if (pts >= 60) return "Very Good!";
        if (pts >= 30) return "Good!";
        return "Nice!";
    }

    // ---------- spawnParticles (single definition) ----------
    private void spawnParticles(double x, double y, Color base, int count) {
        for (int i = 0; i < count; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double sp = 80 + rnd.nextDouble() * 220;
            double vx = Math.cos(ang) * sp;
            double vy = Math.sin(ang) * sp * 0.6;
            particles.add(new Particle(x, y, vx, vy, base, 0.6 + rnd.nextDouble() * 0.8));
        }
    }

    // ---------- Painting ----------
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        recomputeLayout();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // sky
        g2.setColor(getBackground()); g2.fillRect(0,0,W,H); drawClouds(g2);

        // ground/hills
        g2.setColor(new Color(40,150,40)); g2.fillRect(0, groundY, W, H-groundY);
        g2.setColor(new Color(34,139,34));
        g2.fillOval((int)(W*0.15), groundY - (int)(H*0.15), (int)(W*0.5), (int)(H*0.18));
        g2.fillOval((int)(W*0.6), groundY - (int)(H*0.18), (int)(W*0.5), (int)(H*0.2));

        // power-ups
        for (PowerUp pu : powerUps) pu.draw(g2);

        // target behind stuck arrows
        target.draw(g2);

        // stuck arrows
        for (Arrow a : arrows) if (a.stuck) a.draw(g2);

        // archer
        drawStickmanArcher(g2);

        // flying arrows
        for (Arrow a : arrows) if (!a.stuck) a.draw(g2);

        // particles & popups
        for (Particle p : particles) p.draw(g2);
        for (TextPopup tp : popups) tp.draw(g2);

        // HUD & overlays
        drawHUD(g2);
        if (state == State.MENU) drawMenuOverlay(g2);
        else if (state == State.PAUSED) drawPausedOverlay(g2);
        else if (state == State.GAME_OVER) drawGameOverOverlay(g2);

        g2.dispose();
    }

    private void drawClouds(Graphics2D g2) {
        g2.setColor(new Color(255,255,255,230));
        g2.fillOval((int)(W*0.08), (int)(H*0.05), (int)(W*0.12), (int)(H*0.08));
        g2.fillOval((int)(W*0.14), (int)(H*0.02), (int)(W*0.16), (int)(H*0.1));
        g2.fillOval((int)(W*0.68), (int)(H*0.06), (int)(W*0.14), (int)(H*0.08));
        g2.fillOval((int)(W*0.74), (int)(H*0.03), (int)(W*0.16), (int)(H*0.1));
    }

    private void drawStickmanArcher(Graphics2D g2) {
        int headR = (int)(Math.min(W,H)*0.03);
        int bodyLen = (int)(Math.min(W,H)*0.12);
        int cx = archerX;
        int cy = archerY - bodyLen/2;

        g2.setStroke(new BasicStroke(Math.max(2,(int)(W*0.003f)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(60,40,20));
        g2.drawLine(cx, cy + bodyLen/2, cx - (int)(W*0.03), cy + bodyLen);
        g2.drawLine(cx, cy + bodyLen/2, cx + (int)(W*0.03), cy + bodyLen);
        g2.drawLine(cx, cy - headR/2, cx, cy + bodyLen/2);

        g2.setColor(new Color(230,190,150));
        g2.fillOval(cx - headR, cy - bodyLen/2 - headR*2, headR*2, headR*2);

        int shoulderX = cx, shoulderY = cy - bodyLen/4;
        AffineTransform old = g2.getTransform();
        g2.translate(shoulderX, shoulderY);
        g2.rotate(aimAngle);

        int bowLen = (int)(Math.min(W,H)*0.14);
        g2.setStroke(new BasicStroke(Math.max(3,(int)(W*0.006f)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(120,60,20));
        g2.draw(new Arc2D.Double(-bowLen/2, -bowLen, bowLen, bowLen*2, 90, -180, Arc2D.OPEN));

        g2.setColor(Color.DARK_GRAY);
        double pull = 0;
        if (charging) {
            double held = Math.min((System.nanoTime() - chargeStartNanos)/1_000_000_000.0, MAX_CHARGE);
            double t = held / MAX_CHARGE; pull = 6 + 36 * Math.sin(t * Math.PI * 0.5);
        }
        g2.setStroke(new BasicStroke(2));
        g2.drawLine(0, -bowLen/2, (int)-pull, 0);
        g2.drawLine(0, bowLen/2, (int)-pull, 0);
        if (charging) {
            g2.setStroke(new BasicStroke(4));
            g2.setColor(new Color(190,140,60));
            g2.drawLine((int)-pull, 0, (int)-pull - arrowLength, 0);
        } else {
            g2.setStroke(new BasicStroke(4));
            g2.setColor(new Color(190,140,60));
            g2.drawLine(10, 0, 10 + arrowLength, 0);
        }
        g2.setTransform(old);

        g2.setStroke(new BasicStroke(Math.max(2,(int)(W*0.004f)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int handX = shoulderX + (int)(Math.cos(aimAngle) * (charging ? -pullEstimate() : -40));
        int handY = shoulderY + (int)(Math.sin(aimAngle) * (charging ? -pullEstimate() : -40));
        g2.drawLine(shoulderX, shoulderY, handX, handY);
        int bowHandX = shoulderX + (int)(Math.cos(aimAngle+Math.PI/2) * 10);
        int bowHandY = shoulderY + (int)(Math.sin(aimAngle+Math.PI/2) * 10);
        g2.drawLine(shoulderX, shoulderY, bowHandX, bowHandY);
    }

    private int pullEstimate() {
        if (!charging) return 40;
        double held = Math.min((System.nanoTime() - chargeStartNanos)/1_000_000_000.0, MAX_CHARGE);
        double t = held / MAX_CHARGE;
        double pull = 6 + 36 * Math.sin(t * Math.PI * 0.5);
        return (int)pull + 40;
    }

    private void drawHUD(Graphics2D g2) {
        g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, W/48)));
        g2.setColor(Color.WHITE);
        g2.drawString("Score: " + score, 18, 28);
        g2.drawString("High: " + highScore, 18, 52);
        g2.drawString("Arrows: " + arrowsLeft, 18, 76);
        g2.drawString("Time: " + timeLeft + "s", 18, 100);
        g2.drawString("Difficulty: " + difficultyBox.getSelectedItem(), 18, 124);

        if (charging) {
            double held = Math.min((System.nanoTime() - chargeStartNanos)/1_000_000_000.0, MAX_CHARGE);
            double ratio = held / MAX_CHARGE;
            int w = Math.max(120, (int)(W*0.23));
            g2.setColor(Color.DARK_GRAY); g2.fillRect(18, 132, w, 16);
            g2.setColor(Color.ORANGE); g2.fillRect(18, 132, (int)(w * ratio), 16);
            g2.setColor(Color.WHITE); g2.drawRect(18, 132, w, 16);
            g2.drawString(String.format("Power: %.0f%%", ratio * 100), 18 + w + 8, 144);
        }
    }

    private void drawMenuOverlay(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,160)); g2.fillRect(0,0,W,H);
        g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(28, W/20)));
        g2.drawString("ARCHERY CHALLENGE", W/2 - 260, H/2 - 60);
        g2.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, W/40)));
        g2.drawString("Select difficulty and press Start", W/2 - 160, H/2 - 20);
        g2.drawString("Drag (or touch) to aim, hold to charge, release to fire", W/2 - 240, H/2 + 10);
    }
    private void drawPausedOverlay(Graphics2D g2) { g2.setColor(new Color(0,0,0,150)); g2.fillRect(0,0,W,H); g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(24,W/30))); g2.drawString("PAUSED", W/2 - 60, H/2); }
    private void drawGameOverOverlay(Graphics2D g2) { g2.setColor(new Color(0,0,0,200)); g2.fillRect(0,0,W,H); g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(24,W/30))); g2.drawString("GAME OVER", W/2 - 80, H/2 - 20); g2.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12,W/40))); g2.drawString("Final Score: " + score, W/2 - 60, H/2 + 12); g2.drawString("High Score: " + highScore, W/2 - 60, H/2 + 36); }

    // ---------- inner classes ----------
    private class Arrow {
        double x,y,vx,vy,angle;
        boolean stuck = false;
        Target stuckTo = null;
        double localOffsetX, localOffsetY;
        Arrow(double x,double y,double vx,double vy,double angle) { this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.angle=angle; }
        void draw(Graphics2D g2) {
            AffineTransform old = g2.getTransform();
            g2.translate(x,y); g2.rotate(angle);
            g2.setStroke(new BasicStroke(Math.max(2,(int)(W*0.004f)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(140,100,60));
            g2.drawLine((int)(-arrowLength/2), 0, (int)(arrowLength/2 - 8), 0);
            g2.setColor(new Color(70,70,70));
            int tip = (int)(arrowLength/2);
            g2.fillPolygon(new int[] {tip, tip-12, tip-12}, new int[] {0, -8, 8}, 3);
            g2.setColor(new Color(230,230,230));
            g2.fillRect((int)(-arrowLength/2 - 8), -6, 8, 4);
            g2.fillRect((int)(-arrowLength/2 - 8), 4, 8, 4);
            g2.setTransform(old);
        }
        void stickTo(Target t, double tipX, double tipY) {
            stuck = true; stuckTo = t; localOffsetX = tipX - t.x; localOffsetY = tipY - t.y;
            x = tipX - Math.cos(angle) * arrowLength/2.0; y = tipY - Math.sin(angle) * arrowLength/2.0;
            vx = vy = 0;
        }
        void updateStuck() {
            if (stuckTo == null) return;
            double tipX = stuckTo.x + localOffsetX; double tipY = stuckTo.y + localOffsetY;
            x = tipX - Math.cos(angle) * arrowLength/2.0; y = tipY - Math.sin(angle) * arrowLength/2.0;
        }
    }

    private class Target {
        double x,y; int radius; double vx; int[] ringRadii; int[] ringPoints; double wobble = 0;
        Target(double x,double y,int r,double vx) { this.x=x; this.y=y; this.radius=r; this.vx=vx; }
        void setupRings() { ringRadii = new int[] { radius, (int)(radius*0.72), (int)(radius*0.48), (int)(radius*0.28) }; ringPoints = new int[] { 10, 30, 60, 100 }; }
        void update(double dt) { x += vx * dt; wobble *= 0.94; if (x < -radius - 40) { x = W + radius + 40; y = 120 + rnd.nextInt(Math.max(1, groundY - 180)); } }
        boolean hit(double px,double py) { return Math.hypot(px - x, py - y) <= radius; }
        int getPointsForHit(double px,double py) { double d = Math.hypot(px - x, py - y); for (int i = ringRadii.length - 1; i >= 0; i--) if (d <= ringRadii[i]) return ringPoints[i]; return 0; }
        void wobble() { wobble = 8 + rnd.nextDouble()*18; }
        void draw(Graphics2D g2) {
            Color[] cols = new Color[] { Color.WHITE, Color.BLACK, new Color(40,80,200), Color.RED };
            for (int i=0;i<ringRadii.length;i++) { int r = ringRadii[i]; g2.setColor(cols[i%cols.length]); g2.fillOval((int)(x - r), (int)(y - r + Math.sin(wobble*0.07)), r*2, r*2); }
            int cent = Math.max(6, radius/10);
            g2.setColor(Color.YELLOW); g2.fillOval((int)x - cent, (int)(y - cent + Math.sin(wobble*0.07)), cent*2, cent*2);
            g2.setColor(Color.DARK_GRAY); g2.setStroke(new BasicStroke(Math.max(1,(int)(W*0.002f))));
            g2.drawOval((int)(x - radius), (int)(y - radius + Math.sin(wobble*0.07)), radius*2, radius*2);
        }
    }

    private class Particle {
        double x,y,vx,vy; Color c; double life, init;
        Particle(double x,double y,double vx,double vy,Color c,double life) { this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.c=c;this.life=life;this.init=life; }
        void update(double dt) { vy += gravity * dt; vx -= vx * 1.2 * dt; vy -= vy * 0.6 * dt; x += vx * dt; y += vy * dt; life -= dt; }
        void draw(Graphics2D g2) { float a = (float)Math.max(0, life/init); Color col = new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(255*a)); g2.setColor(col); int s = (int)(3 + 6*(1 - a)); g2.fillOval((int)x - s/2, (int)y - s/2, s, s); }
    }

    private class PowerUp {
        double x,y; double vx = -100; double bob = 0;
        PowerUp(double x,double y) { this.x=x; this.y=y; }
        void update(double dt) { x += vx * dt; bob += dt; }
        void draw(Graphics2D g2) { int s = Math.max(16, (int)(W*0.03)); g2.setColor(Color.GREEN); g2.fillOval((int)(x - s/2), (int)(y - s/2 + Math.sin(bob*3)*3), s, s); g2.setColor(Color.BLACK); g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, W/60))); g2.drawString("+A", (int)x - s/4, (int)(y + 6 + Math.sin(bob*3)*3)); }
        boolean hit(double px,double py) { return Math.hypot(px-x, py-y) < Math.max(18, W*0.03); }
        void apply(ArcheryChallenge g) { arrowsLeft += 2; popups.add(new TextPopup(x,y,"+2 Arrows!",900)); }
    }

    private class TextPopup { double x,y; String text; double life; TextPopup(double x,double y,String t,double life) { this.x=x;this.y=y;this.text=t;this.life=life; } void draw(Graphics2D g2) { float a = (float)Math.max(0, life/1000.0); g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(12,W/48))); g2.setColor(new Color(255,255,255,(int)(255*a))); g2.drawString(text, (int)x + 8, (int)y - 8); } }

    // ---------- High score persistence ----------
    private void loadHighScore() {
        File f = new File(HIGHSCORE_FILE);
        if (!f.exists()) { highScore = 0; return; }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) { String s = br.readLine(); if (s != null) highScore = Integer.parseInt(s.trim()); }
        catch (Exception ex) { highScore = 0; }
    }
    private void saveHighScore() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(HIGHSCORE_FILE))) { pw.println(highScore); } catch (Exception ex) { /* ignore */ }
    }

    // ---------- Entry ----------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Archery Challenge - Stickman");
            ArcheryChallenge game = new ArcheryChallenge();
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(game);
            f.pack();
            f.setResizable(true);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
