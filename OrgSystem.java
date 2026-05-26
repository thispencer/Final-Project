package OrgSystem;

// ── STANDARD JAVA IMPORTS ─────────────────────────────────
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

// ── ZXING IMPORTS (specific — avoids Dimension conflict) ──
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;

// ── WEBCAM IMPORTS ────────────────────────────────────────
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamPanel;
import com.github.sarxos.webcam.WebcamResolution;

public class OrgSystem {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            UIManager.put("Panel.background",      new Color(245, 247, 252));
            UIManager.put("OptionPane.background", Color.WHITE);
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}

class Const {
    static final String[] POSITIONS = {
        "Member", "President", "Vice President", "Secretary",
        "Treasurer", "Public Relations Officer", "Auditor", "Sergeant-at-Arms"
    };

    static String currentSchoolYear() {
        Calendar cal  = Calendar.getInstance();
        int       year = cal.get(Calendar.YEAR);
        int       month= cal.get(Calendar.MONTH);
        if (month >= 5) { return year + "-" + (year + 1); }
        else            { return (year - 1) + "-" + year; }
    }
}

class DB {
    private static final String URL =
        "jdbc:mysql://localhost:3306/org_management" +
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "";
    private static Connection conn;

    static Connection get() throws SQLException {
        if (conn == null || conn.isClosed()) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                conn = DriverManager.getConnection(URL, USER, PASS);
            } catch (ClassNotFoundException e) {
                throw new SQLException("MySQL JAR missing!\nAdd mysql-connector-j.jar to Libraries.");
            }
        }
        return conn;
    }

    static void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }
}

class Member {
    int    id;
    String studentId, firstName, lastName, gender;
    String course, email, status, position, qrPath;
    int    yearLevel;

    Member() {}

    Member(int id, String sid, String fn, String ln, String g,
           String c, int y, String em, String st, String pos, String qr) {
        this.id = id; studentId = sid;
        firstName = fn; lastName = ln; gender = g;
        course = c; yearLevel = y; email = em;
        status = st; position = pos; qrPath = qr;
    }

    String toQRString() {
        return studentId  + "|" + firstName + "|" + lastName + "|"
             + course     + "|" + yearLevel + "|" + status   + "|"
             + (position != null ? position : "Member");
    }

    static Member fromQRString(String qrText) {
        if (qrText == null || qrText.trim().isEmpty()) return null;
        String[] p = qrText.trim().split("\\|");
        if (p.length < 6) return null;
        Member m    = new Member();
        m.studentId = p[0].trim(); m.firstName = p[1].trim();
        m.lastName  = p[2].trim(); m.course    = p[3].trim();
        try { m.yearLevel = Integer.parseInt(p[4].trim()); }
        catch (NumberFormatException e) { m.yearLevel = 1; }
        m.status   = p[5].trim();
        m.position = p.length > 6 ? p[6].trim() : "Member";
        return m;
    }
}

class HistoryEntry {
    int    historyId, memberId;
    String studentId, fullName, position;
    String schoolYear, dateChanged, changedBy, notes;

    HistoryEntry(int hid, int mid, String sid, String name,
                 String pos, String sy, String date, String by, String notes) {
        historyId   = hid;  memberId   = mid;
        studentId   = sid;  fullName   = name;
        position    = pos;  schoolYear = sy;
        dateChanged = date; changedBy  = by;
        this.notes  = notes;
    }
}

// ============================================================
//  DAO — ALL queries now scoped by username (created_by)
// ============================================================
class DAO {
    static boolean login(String user, String pass) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT 1 FROM users WHERE username=? AND password=?")) {
            ps.setString(1, user); ps.setString(2, pass);
            return ps.executeQuery().next();
        } catch (SQLException e) { showErr("Login: " + e.getMessage()); return false; }
    }

    // ── GET ALL MEMBERS — filtered by owner ─────────────────
    static List<Member> getAll(String username) {
        List<Member> list = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT * FROM members WHERE created_by=? ORDER BY last_name, first_name")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapMember(rs));
            }
        } catch (SQLException e) { showErr(e.getMessage()); }
        return list;
    }

    // ── SEARCH — filtered by owner ───────────────────────────
    static List<Member> search(String kw, String username) {
        List<Member> list = new ArrayList<>();
        String p   = "%" + kw + "%";
        String sql = "SELECT * FROM members WHERE created_by=? AND (" +
                     "student_id LIKE ? OR first_name LIKE ? OR last_name LIKE ? " +
                     "OR course LIKE ? OR status LIKE ? OR position LIKE ?) " +
                     "ORDER BY last_name";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1, username);
            for (int i = 2; i <= 7; i++) ps.setString(i, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapMember(rs));
            }
        } catch (SQLException e) { showErr(e.getMessage()); }
        return list;
    }

    // ── STUDENT ID UNIQUENESS — scoped per user ──────────────
    static boolean studentIdExists(String sid, int excludeId, String username) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT 1 FROM members WHERE student_id=? AND id!=? AND created_by=?")) {
            ps.setString(1, sid); ps.setInt(2, excludeId); ps.setString(3, username);
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    // ── ADD MEMBER — stamps created_by ───────────────────────
    static boolean add(Member m, String addedBy) {
        if (studentIdExists(m.studentId, 0, addedBy)) {
            showErr("Student ID '" + m.studentId + "' already exists!"); return false;
        }
        String sql = "INSERT INTO members " +
            "(student_id,first_name,last_name,gender,course," +
            "year_level,email,status,position,qr_path,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = DB.get().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1,m.studentId); ps.setString(2,m.firstName);
            ps.setString(3,m.lastName);  ps.setString(4,m.gender);
            ps.setString(5,m.course);    ps.setInt(6,m.yearLevel);
            ps.setString(7,m.email);     ps.setString(8,m.status);
            ps.setString(9,m.position);  ps.setString(10,m.qrPath);
            ps.setString(11,addedBy);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) {
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    m.id = keys.getInt(1);
                    addHistory(m.id, m.studentId, m.firstName + " " + m.lastName,
                        m.position, Const.currentSchoolYear(), addedBy, "Member added to system");
                }
            }
            return ok;
        } catch (SQLException e) { showErr(e.getMessage()); return false; }
    }

    // ── UPDATE MEMBER — only owner can update ────────────────
    static boolean update(Member m, String oldPosition, String updatedBy) {
        if (studentIdExists(m.studentId, m.id, updatedBy)) {
            showErr("Student ID '" + m.studentId + "' already used by another member!"); return false;
        }
        String sql = "UPDATE members SET student_id=?,first_name=?,last_name=?," +
            "gender=?,course=?,year_level=?,email=?,status=?,position=?,qr_path=? " +
            "WHERE id=? AND created_by=?";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1,m.studentId); ps.setString(2,m.firstName);
            ps.setString(3,m.lastName);  ps.setString(4,m.gender);
            ps.setString(5,m.course);    ps.setInt(6,m.yearLevel);
            ps.setString(7,m.email);     ps.setString(8,m.status);
            ps.setString(9,m.position);  ps.setString(10,m.qrPath);
            ps.setInt(11,m.id);          ps.setString(12,updatedBy);
            boolean ok = ps.executeUpdate() > 0;
            if (ok && !m.position.equals(oldPosition)) {
                addHistory(m.id, m.studentId, m.firstName + " " + m.lastName,
                    m.position, Const.currentSchoolYear(), updatedBy,
                    "Position changed from " + oldPosition + " to " + m.position);
            }
            return ok;
        } catch (SQLException e) { showErr(e.getMessage()); return false; }
    }

    // ── DELETE — only owner can delete ───────────────────────
    static boolean delete(int id, String username) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM members WHERE id=? AND created_by=?")) {
            ps.setInt(1, id); ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { showErr(e.getMessage()); return false; }
    }

    // ── COUNT — scoped per user ──────────────────────────────
    static int count(String where, String username) {
        String base = "SELECT COUNT(*) FROM members WHERE created_by=?";
        String sql  = where == null ? base : base + " AND " + where;
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) { return 0; }
    }

    static void addHistory(int memberId, String sid, String name,
                           String position, String schoolYear, String changedBy, String notes) {
        String sql = "INSERT INTO member_history " +
            "(member_id,student_id,full_name,position,school_year,changed_by,notes,created_by) " +
            "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setInt(1, memberId);  ps.setString(2, sid);
            ps.setString(3, name);   ps.setString(4, position);
            ps.setString(5, schoolYear); ps.setString(6, changedBy);
            ps.setString(7, notes);  ps.setString(8, changedBy);
            ps.executeUpdate();
        } catch (SQLException e) { System.err.println("History log error: " + e.getMessage()); }
    }

    static List<HistoryEntry> getHistory(int memberId) {
        List<HistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM member_history WHERE member_id=? ORDER BY date_changed DESC";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HistoryEntry(
                        rs.getInt("history_id"), rs.getInt("member_id"),
                        rs.getString("student_id"), rs.getString("full_name"),
                        rs.getString("position"), rs.getString("school_year"),
                        rs.getString("date_changed"), rs.getString("changed_by"),
                        rs.getString("notes")));
                }
            }
        } catch (SQLException e) { showErr(e.getMessage()); }
        return list;
    }

    // ── ALL HISTORY — scoped per user ────────────────────────
    static List<HistoryEntry> getAllHistory(String username) {
        List<HistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM member_history WHERE changed_by=? ORDER BY date_changed DESC LIMIT 200";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HistoryEntry(
                        rs.getInt("history_id"), rs.getInt("member_id"),
                        rs.getString("student_id"), rs.getString("full_name"),
                        rs.getString("position"), rs.getString("school_year"),
                        rs.getString("date_changed"), rs.getString("changed_by"),
                        rs.getString("notes")));
                }
            }
        } catch (SQLException e) { showErr(e.getMessage()); }
        return list;
    }

    private static Member mapMember(ResultSet rs) throws SQLException {
        return new Member(
            rs.getInt("id"), rs.getString("student_id"),
            rs.getString("first_name"), rs.getString("last_name"),
            rs.getString("gender"),     rs.getString("course"),
            rs.getInt("year_level"),    rs.getString("email"),
            rs.getString("status"),     rs.getString("position"),
            rs.getString("qr_path"));
    }

    static void showErr(String msg) {
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /* ── REGISTER NEW USER ──────────────────────────────── */
    static boolean registerUser(String username, String password, String securityQuestion, String securityAnswer) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT 1 FROM users WHERE username=?")) {
            ps.setString(1, username);
            if (ps.executeQuery().next()) {
                showErr("Username '" + username + "' is already taken. Please choose another.");
                return false;
            }
        } catch (SQLException e) { showErr(e.getMessage()); return false; }

        String sql = "INSERT INTO users (username, password, security_question, security_answer) VALUES (?,?,?,?)";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            ps.setString(3, securityQuestion);
            ps.setString(4, securityAnswer.trim().toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getMessage().contains("Unknown column")) {
                showErr("Please run the upgrade SQL first!\n\n" +
                    "Add these columns to your users table in phpMyAdmin:\n\n" +
                    "ALTER TABLE users ADD COLUMN security_question VARCHAR(200) DEFAULT NULL;\n" +
                    "ALTER TABLE users ADD COLUMN security_answer VARCHAR(100) DEFAULT NULL;");
            } else {
                showErr(e.getMessage());
            }
            return false;
        }
    }

    /* ── VERIFY SECURITY ANSWER FOR PASSWORD RECOVERY ───── */
    static String recoverPassword(String username, String securityAnswer) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT password, security_answer FROM users WHERE username=?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            String storedAnswer = rs.getString("security_answer");
            String password     = rs.getString("password");
            if (storedAnswer != null && storedAnswer.equals(securityAnswer.trim().toLowerCase())) {
                return password;
            }
            return "";
        } catch (SQLException e) { showErr(e.getMessage()); return null; }
    }

    /* ── GET SECURITY QUESTION FOR A USERNAME ────────────── */
    static String getSecurityQuestion(String username) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT security_question FROM users WHERE username=?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("security_question");
            return null;
        } catch (SQLException e) { return null; }
    }
}

class QRGen {
    static final String QR_FOLDER = "qr_codes/";

    static String save(Member m) {
        try {
            new File(QR_FOLDER).mkdirs();
            String text = m.toQRString();
            Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300, hints);
            String path = QR_FOLDER + m.studentId.replace("/", "-") + ".png";
            MatrixToImageWriter.writeToPath(matrix, "PNG", new File(path).toPath());
            return path;
        } catch (Exception e) { return ""; }
    }

    static BufferedImage preview(String text) {
        try {
            if (text == null || text.isEmpty()) return null;
            Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 200, 200, hints);
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (Exception e) { return null; }
    }

    static String scanFromFile(File file) {
        try {
            BufferedImage img  = ImageIO.read(file);
            LuminanceSource src= new BufferedImageLuminanceSource(img);
            BinaryBitmap bmp   = new BinaryBitmap(new HybridBinarizer(src));
            return new MultiFormatReader().decode(bmp).getText();
        } catch (Exception e) { return null; }
    }
}

class Col {
    static final Color NAVY      = new Color(15,  35,  75);
    static final Color BLUE      = new Color(30,  90,  200);
    static final Color LIGHT     = new Color(245, 247, 252);
    static final Color WHITE     = Color.WHITE;
    static final Color DARK      = new Color(20,  30,  60);
    static final Color GREY      = new Color(120, 130, 155);
    static final Color GREEN     = new Color(34,  197, 94);
    static final Color RED       = new Color(220, 50,  50);
    static final Color GOLD      = new Color(255, 185, 0);
    static final Color ORANGE    = new Color(234, 88,  12);
    static final Color PURPLE    = new Color(124, 58,  237);
    static final Color TEAL      = new Color(13,  148, 136);
    static final Color BORDER    = new Color(210, 215, 230);
    static final Color ROW_ALT   = new Color(248, 250, 255);
    static final Color SEL_BG    = new Color(224, 234, 255);
    static final Color STAT_BLUE = new Color(80,  130, 255);
}

class UI {
    static JLabel lbl(String t, int sz, int style, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(new Font("Segoe UI", style, sz));
        l.setForeground(c); return l;
    }

    static void styleField(JTextField f) {
        f.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        f.setBackground(new Color(250,251,254));
        f.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(Col.BORDER,1,true), new EmptyBorder(7,11,7,11)));
    }

    static JComboBox<String> combo(String... items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        cb.setBackground(new Color(250,251,254));
        return cb;
    }

    static JButton btn(String text, Color bg) {
        return btn(text, bg, 12, 115, 36);
    }

    static JButton btn(String text, Color bg, int fontSize, int w, int h) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed()  ? bg.darker()
                          : getModel().isRollover() ? bg.brighter() : bg);
                g2.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
                g2.setColor(Color.WHITE); g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()-fm.stringWidth(getText()))/2,
                    (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        b.setFont(new Font("Segoe UI", Font.BOLD, fontSize));
        b.setOpaque(false); b.setContentAreaFilled(false);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new java.awt.Dimension(w, h));
        return b;
    }
}

class QRPreview extends JPanel {
    private BufferedImage img;
    private final JLabel  hint;

    QRPreview() {
        setPreferredSize(new java.awt.Dimension(165,165));
        setBackground(Col.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(Col.BORDER,1,true), new EmptyBorder(5,5,5,5)));
        setLayout(new BorderLayout());
        hint = new JLabel("<html><center>QR preview<br>appears here</center></html>",
                          SwingConstants.CENTER);
        hint.setFont(new Font("Segoe UI",Font.PLAIN,10));
        hint.setForeground(Col.GREY);
        add(hint, BorderLayout.CENTER);
    }

    void show(String text) { img = QRGen.preview(text); hint.setVisible(false); repaint(); }
    void reset()           { img=null; hint.setVisible(true); repaint(); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (img != null) {
            int s = Math.min(getWidth(),getHeight())-10;
            g.drawImage(img,(getWidth()-s)/2,(getHeight()-s)/2,s,s,this);
        }
    }
}

class QRScanDialog extends JDialog {

    interface OnScanned { void done(Member m); }

    private Webcam      cam;
    private WebcamPanel camPanel;
    private volatile boolean running = true;
    private final OnScanned  cb;
    private JLabel statusLbl;

    QRScanDialog(Frame parent, OnScanned cb) {
        super(parent, "Scan Student QR Code", true);
        this.cb = cb;
        setSize(520, 600);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(8,8));
        getContentPane().setBackground(Col.NAVY);
        buildUI();
    }

    private void buildUI() {
        JLabel title = UI.lbl("Point camera at student ID QR code", 15, Font.BOLD, Col.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(14,10,8,10));

        statusLbl = UI.lbl("Initializing camera...", 13, Font.PLAIN, new Color(180,200,255));
        statusLbl.setHorizontalAlignment(SwingConstants.CENTER);

        boolean cameraOk = tryOpenCamera();

        JButton fileBtn  = UI.btn("Scan from Image File instead", Col.TEAL, 12, 260, 38);
        fileBtn.addActionListener(e -> scanFromFile());

        JButton closeBtn = UI.btn("Close", Col.RED, 12, 100, 36);
        closeBtn.addActionListener(e -> closeDialog());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        btnPanel.setOpaque(false);
        btnPanel.add(fileBtn); btnPanel.add(closeBtn);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(4,10,10,10));
        south.add(statusLbl, BorderLayout.NORTH);
        south.add(btnPanel,  BorderLayout.CENTER);

        add(title, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);

        if (cameraOk) {
            add(camPanel, BorderLayout.CENTER);
            startScanLoop();
        } else {
            add(buildNoCameraPanel(), BorderLayout.CENTER);
            SwingUtilities.invokeLater(() ->
                statusLbl.setText("No webcam detected — use 'Scan from Image File'"));
        }

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeDialog(); }
        });
    }

    private boolean tryOpenCamera() {
        try {
            cam = Webcam.getDefault();
            if (cam == null) return false;
            cam.setViewSize(WebcamResolution.VGA.getSize());
            cam.open(true);
            camPanel = new WebcamPanel(cam);
            camPanel.setFPSDisplayed(false);
            camPanel.setPreferredSize(new java.awt.Dimension(480, 400));
            camPanel.setBorder(new LineBorder(Col.GOLD, 2, true));
            SwingUtilities.invokeLater(() -> statusLbl.setText("Scanning... hold QR code steady"));
            return true;
        } catch (Exception e) { System.err.println("Camera error: " + e.getMessage()); return false; }
    }

    private void startScanLoop() {
        new Thread(() -> {
            MultiFormatReader reader = new MultiFormatReader();
            while (running) {
                try {
                    Thread.sleep(150);
                    if (cam == null || !cam.isOpen()) break;
                    BufferedImage frame = cam.getImage();
                    if (frame == null) continue;
                    LuminanceSource src = new BufferedImageLuminanceSource(frame);
                    BinaryBitmap   bmp  = new BinaryBitmap(new HybridBinarizer(src));
                    Result result = reader.decode(bmp);
                    String text    = result.getText();
                    Member scanned = Member.fromQRString(text);
                    if (scanned != null) {
                        running = false;
                        SwingUtilities.invokeLater(() -> { cb.done(scanned); closeDialog(); });
                    } else {
                        SwingUtilities.invokeLater(() -> statusLbl.setText("QR found but wrong format"));
                    }
                } catch (NotFoundException ignored) {
                } catch (InterruptedException e) { break;
                } catch (Exception e) { System.err.println("Scan loop: " + e.getMessage()); }
            }
        }, "QR-Scanner").start();
    }

    private void scanFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select QR Code Image");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Image files", "png","jpg","jpeg","bmp"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File   file   = fc.getSelectedFile();
            String result = QRGen.scanFromFile(file);
            if (result != null) {
                Member m = Member.fromQRString(result);
                if (m != null) { running = false; cb.done(m); closeDialog(); }
                else {
                    statusLbl.setText("QR format not recognized");
                    JOptionPane.showMessageDialog(this,
                        "QR code found but format is wrong.\nExpected: student_id|name|course|year|status|position",
                        "Wrong Format", JOptionPane.WARNING_MESSAGE);
                }
            } else {
                statusLbl.setText("No QR code found in that image");
                JOptionPane.showMessageDialog(this,
                    "Could not find a QR code in the selected image.",
                    "No QR Found", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private JPanel buildNoCameraPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(new Color(20,40,80));
        p.setBorder(new EmptyBorder(20,20,20,20));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx=0; c.gridy=GridBagConstraints.RELATIVE;
        c.insets=new Insets(8,10,8,10); c.anchor=GridBagConstraints.CENTER;
        JLabel head = UI.lbl("No Webcam Detected", 16, Font.BOLD, Col.WHITE);
        JLabel msg  = new JLabel(
            "<html><center style='color:#aac;font-size:12px'>" +
            "Possible causes:<br><br>" +
            "1. Webcam not plugged in<br>" +
            "2. Camera in use by another app<br>" +
            "   (close Teams, Zoom, Camera app)<br>" +
            "3. Driver not installed<br>" +
            "4. Windows Privacy Settings blocking<br><br>" +
            "<b style='color:#ffd'>Fix:</b> Go to Windows Settings<br>" +
            "Privacy &gt; Camera &gt; Turn ON<br>" +
            "'Allow desktop apps to access camera'" +
            "</center></html>");
        msg.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        p.add(head, c); p.add(msg, c);
        return p;
    }

    private void closeDialog() {
        running = false;
        if (cam != null && cam.isOpen()) cam.close();
        dispose();
    }
}

class HistoryDialog extends JDialog {

    HistoryDialog(Frame parent, int memberId, String memberName, List<HistoryEntry> entries) {
        super(parent, "Position History — " + memberName, true);
        setSize(780, 480);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10,10));
        getContentPane().setBackground(Col.LIGHT);
        buildUI(entries);
    }

    private void buildUI(List<HistoryEntry> entries) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Col.NAVY);
        header.setPreferredSize(new java.awt.Dimension(0,52));
        header.setBorder(new EmptyBorder(0,20,0,20));
        JLabel title = UI.lbl("Position History Log", 16, Font.BOLD, Col.WHITE);
        JLabel sub   = UI.lbl("All position changes are recorded here", 12, Font.PLAIN, new Color(180,200,255));
        JPanel ht = new JPanel(new BorderLayout()); ht.setOpaque(false);
        ht.add(title, BorderLayout.NORTH); ht.add(sub, BorderLayout.SOUTH);
        header.add(ht, BorderLayout.WEST);

        String[] cols = {"School Year","Position","Date Changed","Changed By","Notes"};
        DefaultTableModel model = new DefaultTableModel(cols,0) {
            @Override public boolean isCellEditable(int r,int c){return false;}
        };
        for (HistoryEntry e : entries) {
            model.addRow(new Object[]{ e.schoolYear, e.position, e.dateChanged, e.changedBy, e.notes });
        }

        JTable tbl = new JTable(model);
        tbl.setFont(new Font("Segoe UI",Font.PLAIN,13));
        tbl.setRowHeight(28);
        tbl.getTableHeader().setFont(new Font("Segoe UI",Font.BOLD,12));
        tbl.getTableHeader().setBackground(new Color(240,244,255));
        tbl.getTableHeader().setForeground(Col.NAVY);
        tbl.setGridColor(new Color(235,238,248));
        tbl.setShowHorizontalLines(true); tbl.setShowVerticalLines(false);
        tbl.setSelectionBackground(Col.SEL_BG);

        tbl.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t,Object v,boolean sel,boolean foc,int row,int col){
                super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                setBorder(new EmptyBorder(0,10,0,10));
                if (!sel) {
                    String pos = (String)t.getValueAt(row,1);
                    if ("President".equals(pos)) {
                        setBackground(new Color(255,247,220)); setForeground(new Color(120,70,0));
                    } else if ("Vice President".equals(pos)) {
                        setBackground(new Color(235,245,255)); setForeground(new Color(20,60,160));
                    } else {
                        setBackground(row%2==0 ? Col.WHITE : Col.ROW_ALT); setForeground(Col.DARK);
                    }
                }
                return this;
            }
        });

        int[] widths = {110,160,155,110,200};
        for (int i=0;i<widths.length;i++) tbl.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JScrollPane scroll = new JScrollPane(tbl);
        scroll.setBorder(new LineBorder(new Color(225,228,245),1));

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT,14,10));
        info.setBackground(new Color(250,251,254));
        info.setBorder(new LineBorder(new Color(225,228,240),1));
        JLabel total = UI.lbl("Total changes: " + entries.size(), 12, Font.BOLD, Col.DARK);
        info.add(total);
        if (!entries.isEmpty()) {
            JLabel latest = UI.lbl("Latest position: " + entries.get(0).position, 12, Font.PLAIN, Col.NAVY);
            info.add(UI.lbl("  |  ", 12, Font.PLAIN, Col.GREY));
            info.add(latest);
        }

        JButton closeBtn = UI.btn("Close", Col.NAVY, 13, 100, 36);
        closeBtn.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT,12,8));
        footer.setBackground(Col.LIGHT); footer.add(closeBtn);

        JPanel center = new JPanel(new BorderLayout(0,8));
        center.setBackground(Col.LIGHT);
        center.setBorder(new EmptyBorder(12,16,0,16));
        center.add(info,   BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }
}

// ============================================================
//  ALL HISTORY DIALOG — now scoped to logged-in user
// ============================================================
class AllHistoryDialog extends JDialog {

    AllHistoryDialog(Frame parent, String username) {
        super(parent, "Full Organization History Log", true);
        setSize(900, 560);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10,10));
        getContentPane().setBackground(Col.LIGHT);
        buildUI(username);
    }

    private void buildUI(String username) {
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setBackground(Col.NAVY);
        hdr.setPreferredSize(new java.awt.Dimension(0,52));
        hdr.setBorder(new EmptyBorder(0,20,0,20));
        JLabel t = UI.lbl("Full Organization Position History", 16, Font.BOLD, Col.WHITE);
        hdr.add(t, BorderLayout.CENTER);

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT,12,8));
        filterRow.setBackground(new Color(240,244,255));
        filterRow.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(220,225,240),1), new EmptyBorder(4,8,4,8)));
        JLabel fl = UI.lbl("Filter by School Year:", 12, Font.BOLD, Col.DARK);
        JComboBox<String> yearFilter = UI.combo("All Years","2022-2023","2023-2024","2024-2025","2025-2026");
        JTextField searchF = new JTextField(16); UI.styleField(searchF);
        searchF.setMaximumSize(new java.awt.Dimension(180,34));
        JLabel sl = UI.lbl("Search:", 12, Font.PLAIN, Col.DARK);
        filterRow.add(fl); filterRow.add(yearFilter);
        filterRow.add(Box.createHorizontalStrut(16));
        filterRow.add(sl); filterRow.add(searchF);

        String[] cols = {"#","Student ID","Full Name","Position","School Year","Date","Changed By"};
        DefaultTableModel model = new DefaultTableModel(cols,0){
            @Override public boolean isCellEditable(int r,int c){return false;}
        };
        // ── Scoped to this user only ─────────────────────────
        List<HistoryEntry> all = DAO.getAllHistory(username);
        for (HistoryEntry e : all) {
            model.addRow(new Object[]{ e.historyId, e.studentId, e.fullName,
                e.position, e.schoolYear, e.dateChanged, e.changedBy });
        }

        JTable tbl = new JTable(model);
        tbl.setFont(new Font("Segoe UI",Font.PLAIN,13));
        tbl.setRowHeight(28);
        tbl.getTableHeader().setFont(new Font("Segoe UI",Font.BOLD,12));
        tbl.getTableHeader().setBackground(new Color(240,244,255));
        tbl.getTableHeader().setForeground(Col.NAVY);
        tbl.setGridColor(new Color(235,238,248));
        tbl.setShowHorizontalLines(true); tbl.setShowVerticalLines(false);

        tbl.setDefaultRenderer(Object.class, new DefaultTableCellRenderer(){
            @Override public Component getTableCellRendererComponent(
                    JTable t,Object v,boolean sel,boolean foc,int row,int col){
                super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                setBorder(new EmptyBorder(0,10,0,10));
                if (!sel) {
                    setBackground(row%2==0?Col.WHITE:Col.ROW_ALT);
                    if (col==3 && v!=null) {
                        String p = v.toString();
                        if ("President".equals(p)) { setForeground(new Color(150,90,0)); setFont(getFont().deriveFont(Font.BOLD)); }
                        else if ("Vice President".equals(p)) { setForeground(new Color(20,60,180)); setFont(getFont().deriveFont(Font.BOLD)); }
                        else { setForeground(Col.DARK); setFont(new Font("Segoe UI",Font.PLAIN,13)); }
                    } else { setForeground(Col.DARK); setFont(new Font("Segoe UI",Font.PLAIN,13)); }
                }
                return this;
            }
        });

        int[] widths = {40,110,160,160,110,160,100};
        for (int i=0;i<widths.length;i++) tbl.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JScrollPane scroll = new JScrollPane(tbl);
        scroll.setBorder(new LineBorder(new Color(225,228,245),1));

        JButton closeBtn = UI.btn("Close", Col.NAVY, 13, 100, 36);
        closeBtn.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT,12,8));
        footer.setBackground(Col.LIGHT);
        footer.add(UI.lbl("Total records: " + all.size(), 12, Font.PLAIN, Col.GREY));
        footer.add(closeBtn);

        JPanel center = new JPanel(new BorderLayout(0,8));
        center.setBackground(Col.LIGHT);
        center.setBorder(new EmptyBorder(10,14,0,14));
        center.add(filterRow, BorderLayout.NORTH);
        center.add(scroll,    BorderLayout.CENTER);

        add(hdr,    BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }
}

// ╔══════════════════════════════════════════════════════════╗
// ║  SIGN UP DIALOG — Register a new user account            ║
// ╚══════════════════════════════════════════════════════════╝
class SignUpDialog extends JDialog {

    private static final String[] SECURITY_QUESTIONS = {
        "What is your mother's maiden name?",
        "What was the name of your first pet?",
        "What city were you born in?",
        "What is the name of your elementary school?",
        "What is your favorite book?"
    };

    SignUpDialog(Frame parent) {
        super(parent, "Create New Account", true);
        setSize(460, 520);
        setLocationRelativeTo(parent);
        setResizable(false);
        setLayout(new BorderLayout());
        getContentPane().setBackground(Col.LIGHT);
        buildUI();
    }

    private void buildUI() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Col.NAVY);
        header.setPreferredSize(new java.awt.Dimension(0, 60));
        header.setBorder(new EmptyBorder(0, 24, 0, 24));
        JLabel title = UI.lbl("Create New Account", 17, Font.BOLD, Col.WHITE);
        JLabel sub   = UI.lbl("Fill in all fields to register", 11, Font.PLAIN, new Color(180, 200, 255));
        JPanel ht = new JPanel(new BorderLayout()); ht.setOpaque(false);
        ht.add(title, BorderLayout.NORTH); ht.add(sub, BorderLayout.SOUTH);
        header.add(ht, BorderLayout.CENTER);

        JTextField  newUser = new JTextField(); UI.styleField(newUser);
        JPasswordField newPass = new JPasswordField(); UI.styleField(newPass);
        JPasswordField confPass = new JPasswordField(); UI.styleField(confPass);
        JComboBox<String> secQBox = UI.combo(SECURITY_QUESTIONS);
        secQBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JTextField secAns = new JTextField(); UI.styleField(secAns);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Col.WHITE);
        form.setBorder(new EmptyBorder(20, 28, 20, 28));
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL; gc.insets = new Insets(5, 0, 5, 0);
        gc.gridx = 0; gc.weightx = 1.0;

        gc.gridy = 0; form.add(UI.lbl("Username *", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 1; form.add(newUser, gc);
        gc.gridy = 2; form.add(UI.lbl("Password *", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 3; form.add(newPass, gc);
        gc.gridy = 4; form.add(UI.lbl("Confirm Password *", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 5; form.add(confPass, gc);
        gc.gridy = 6; form.add(UI.lbl("Security Question * (for password recovery)", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 7; form.add(secQBox, gc);
        gc.gridy = 8; form.add(UI.lbl("Your Answer *", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 9; form.add(secAns, gc);

        JLabel note = UI.lbl("Remember your answer — it will be used to recover your password.", 10, Font.ITALIC, Col.GREY);
        note.setBorder(new EmptyBorder(4, 0, 0, 0));
        gc.gridy = 10; form.add(note, gc);

        JButton registerBtn = UI.btn("Create Account", Col.GREEN, 13, 180, 40);
        JButton cancelBtn   = UI.btn("Cancel", new Color(148, 163, 184), 13, 100, 40);

        registerBtn.addActionListener(e -> {
            String u  = newUser.getText().trim();
            String p  = new String(newPass.getPassword());
            String cp = new String(confPass.getPassword());
            String sq = (String) secQBox.getSelectedItem();
            String sa = secAns.getText().trim();

            if (u.isEmpty() || p.isEmpty() || sa.isEmpty()) {
                JOptionPane.showMessageDialog(this, "All fields are required.", "Missing Fields", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (u.length() < 4) {
                JOptionPane.showMessageDialog(this, "Username must be at least 4 characters.", "Too Short", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (p.length() < 6) {
                JOptionPane.showMessageDialog(this, "Password must be at least 6 characters.", "Too Short", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!p.equals(cp)) {
                JOptionPane.showMessageDialog(this, "Passwords do not match. Please re-enter.", "Mismatch", JOptionPane.WARNING_MESSAGE);
                confPass.setText("");
                return;
            }
            if (DAO.registerUser(u, p, sq, sa)) {
                JOptionPane.showMessageDialog(this,
                    "Account created successfully!\n\nUsername: " + u + "\nYou can now log in.",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        });

        cancelBtn.addActionListener(e -> dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        btnRow.setBackground(Col.LIGHT);
        btnRow.add(registerBtn); btnRow.add(cancelBtn);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(form), BorderLayout.CENTER);
        add(btnRow, BorderLayout.SOUTH);
    }
}

// ╔══════════════════════════════════════════════════════════╗
// ║  FORGOT PASSWORD DIALOG — Recover via security question  ║
// ╚══════════════════════════════════════════════════════════╝
class ForgotPasswordDialog extends JDialog {

    private JTextField userField;
    private JLabel     questionLbl;
    private JTextField answerField;
    private JPanel     step2Panel;
    private JButton    recoverBtn;

    ForgotPasswordDialog(Frame parent) {
        super(parent, "Forgot Password", true);
        setSize(420, 420);
        setLocationRelativeTo(parent);
        setResizable(false);
        setLayout(new BorderLayout());
        getContentPane().setBackground(Col.LIGHT);
        buildUI();
    }

    private void buildUI() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Col.NAVY);
        header.setPreferredSize(new java.awt.Dimension(0, 60));
        header.setBorder(new EmptyBorder(0, 24, 0, 24));
        JLabel title = UI.lbl("Forgot Password", 17, Font.BOLD, Col.WHITE);
        JLabel sub   = UI.lbl("Answer your security question to recover access", 11, Font.PLAIN, new Color(180, 200, 255));
        JPanel ht = new JPanel(new BorderLayout()); ht.setOpaque(false);
        ht.add(title, BorderLayout.NORTH); ht.add(sub, BorderLayout.SOUTH);
        header.add(ht, BorderLayout.CENTER);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Col.WHITE);
        form.setBorder(new EmptyBorder(24, 28, 24, 28));
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL; gc.insets = new Insets(5, 0, 5, 0);
        gc.gridx = 0; gc.weightx = 1.0;

        userField = new JTextField(); UI.styleField(userField);
        JButton findBtn = UI.btn("Find Account", Col.BLUE, 12, 140, 36);

        gc.gridy = 0; form.add(UI.lbl("Step 1 — Enter your username", 12, Font.BOLD, Col.DARK), gc);
        gc.gridy = 1; form.add(userField, gc);
        gc.gridy = 2; form.add(findBtn, gc);

        JSeparator sep = new JSeparator();
        sep.setForeground(Col.BORDER);
        gc.gridy = 3; gc.insets = new Insets(12, 0, 12, 0); form.add(sep, gc);
        gc.insets = new Insets(5, 0, 5, 0);

        step2Panel = new JPanel(new GridBagLayout());
        step2Panel.setOpaque(false);
        GridBagConstraints gc2 = new GridBagConstraints();
        gc2.fill = GridBagConstraints.HORIZONTAL; gc2.insets = new Insets(5, 0, 5, 0);
        gc2.gridx = 0; gc2.weightx = 1.0;

        questionLbl = UI.lbl("", 12, Font.BOLD, Col.NAVY);
        questionLbl.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        questionLbl.setBorder(new EmptyBorder(4, 8, 4, 8));
        answerField = new JTextField(); UI.styleField(answerField);
        recoverBtn  = UI.btn("Show My Password", Col.GREEN, 12, 180, 38);

        gc2.gridy = 0; step2Panel.add(UI.lbl("Step 2 — Answer your security question", 12, Font.BOLD, Col.DARK), gc2);
        gc2.gridy = 1; step2Panel.add(questionLbl, gc2);
        gc2.gridy = 2; step2Panel.add(UI.lbl("Your Answer:", 12, Font.PLAIN, Col.DARK), gc2);
        gc2.gridy = 3; step2Panel.add(answerField, gc2);
        gc2.gridy = 4; step2Panel.add(recoverBtn, gc2);
        step2Panel.setVisible(false);

        gc.gridy = 4; form.add(step2Panel, gc);

        findBtn.addActionListener(e -> {
            String u = userField.getText().trim();
            if (u.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter your username.", "Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String q = DAO.getSecurityQuestion(u);
            if (q == null) {
                JOptionPane.showMessageDialog(this,
                    "No account found with that username.\nPlease check and try again.",
                    "Not Found", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (q.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "This account has no security question set.\nPlease contact your administrator.",
                    "No Security Question", JOptionPane.WARNING_MESSAGE);
                return;
            }
            questionLbl.setText("<html><i>" + q + "</i></html>");
            step2Panel.setVisible(true);
            pack(); setSize(420, 480);
        });

        recoverBtn.addActionListener(e -> {
            String u = userField.getText().trim();
            String a = answerField.getText().trim();
            if (a.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter your answer.", "Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String result = DAO.recoverPassword(u, a);
            if (result == null) {
                JOptionPane.showMessageDialog(this, "Account not found.", "Error", JOptionPane.ERROR_MESSAGE);
            } else if (result.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Wrong answer. Please try again.\nMake sure spelling is correct.",
                    "Incorrect Answer", JOptionPane.ERROR_MESSAGE);
                answerField.setText("");
            } else {
                JOptionPane.showMessageDialog(this,
                    "Your password is:\n\n        " + result + "\n\nPlease log in and consider changing it.",
                    "Password Recovered", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        });

        JButton cancelBtn = UI.btn("Cancel", new Color(148, 163, 184), 12, 100, 36);
        cancelBtn.addActionListener(e -> dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        btnRow.setBackground(Col.LIGHT);
        btnRow.add(cancelBtn);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(form), BorderLayout.CENTER);
        add(btnRow, BorderLayout.SOUTH);
    }
}

class LoginFrame extends JFrame {
    private final JTextField     userF = new JTextField();
    private final JPasswordField passF = new JPasswordField();

    LoginFrame() {
        setTitle("Student Organization — Login");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900,580); setLocationRelativeTo(null); setResizable(false);
        buildUI();
    }

    private void buildUI() {
        JPanel root = new JPanel(new GridLayout(1,2));
        root.add(buildBanner()); root.add(buildForm());
        setContentPane(root);
    }

    private JPanel buildBanner() {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Col.NAVY); g2.fillRect(0,0,getWidth(),getHeight());
                g2.setColor(new Color(255,255,255,18));
                g2.fillOval(-50,-50,240,240);
                g2.fillOval(getWidth()-130,getHeight()-130,260,260);
                g2.dispose();
            }
        };
        GridBagConstraints c=new GridBagConstraints();
        c.gridx=0; c.gridy=GridBagConstraints.RELATIVE;
        c.insets=new Insets(9,22,9,22); c.anchor=GridBagConstraints.CENTER;

        JLabel org=UI.lbl("Student Organization",21,Font.BOLD,Col.WHITE);
        JPanel div=new JPanel(); div.setBackground(Col.GOLD);
        div.setPreferredSize(new java.awt.Dimension(52,3)); div.setOpaque(true);
        JLabel sub=UI.lbl("Membership Management System",13,Font.PLAIN,new Color(180,200,255));
        JLabel tag=UI.lbl("Track  ·  Connect  ·  Manage",12,Font.ITALIC,new Color(140,165,230));

        p.add(org,c); p.add(div,c); p.add(sub,c);
        c.insets=new Insets(18,22,0,22); p.add(tag,c);
        return p;
    }

    private JPanel buildForm() {
        JPanel outer=new JPanel(new GridBagLayout());
        outer.setBackground(Col.LIGHT);

        JPanel card=new JPanel();
        card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));
        card.setBackground(Col.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(Col.BORDER,1,true), new EmptyBorder(40,44,40,44)));
        card.setPreferredSize(new java.awt.Dimension(370,430));

        JLabel w=UI.lbl("Welcome Back",22,Font.BOLD,Col.DARK);
        JLabel h=UI.lbl("Sign in to continue",13,Font.PLAIN,Col.GREY);
        w.setAlignmentX(LEFT_ALIGNMENT); h.setAlignmentX(LEFT_ALIGNMENT);

        JLabel ul=UI.lbl("Username",12,Font.BOLD,Col.DARK); ul.setAlignmentX(LEFT_ALIGNMENT);
        UI.styleField(userF);
        userF.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,42));
        userF.setAlignmentX(LEFT_ALIGNMENT);

        JLabel pl=UI.lbl("Password",12,Font.BOLD,Col.DARK); pl.setAlignmentX(LEFT_ALIGNMENT);
        UI.styleField(passF);
        passF.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,42));
        passF.setAlignmentX(LEFT_ALIGNMENT);

        JButton forgotBtn = new JButton("Forgot Password?");
        forgotBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        forgotBtn.setForeground(Col.BLUE);
        forgotBtn.setBorderPainted(false); forgotBtn.setContentAreaFilled(false);
        forgotBtn.setFocusPainted(false);
        forgotBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        forgotBtn.setAlignmentX(LEFT_ALIGNMENT);
        forgotBtn.addActionListener(e -> new ForgotPasswordDialog(LoginFrame.this).setVisible(true));

        JPanel signupRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        signupRow.setOpaque(false); signupRow.setAlignmentX(LEFT_ALIGNMENT);
        signupRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
        JLabel noAccLbl = UI.lbl("Don\'t have an account?", 11, Font.PLAIN, Col.GREY);
        JButton signupBtn = new JButton("Sign Up");
        signupBtn.setFont(new Font("Segoe UI", Font.BOLD, 11));
        signupBtn.setForeground(Col.BLUE);
        signupBtn.setBorderPainted(false); signupBtn.setContentAreaFilled(false);
        signupBtn.setFocusPainted(false);
        signupBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        signupBtn.addActionListener(e -> new SignUpDialog(LoginFrame.this).setVisible(true));
        signupRow.add(noAccLbl); signupRow.add(signupBtn);

        JButton loginBtn=UI.btn("Login",Col.BLUE,14,200,44);
        loginBtn.setAlignmentX(LEFT_ALIGNMENT);
        loginBtn.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,44));
        loginBtn.addActionListener(e->doLogin());
        passF.addActionListener(e->doLogin());
        getRootPane().setDefaultButton(loginBtn);

        card.add(w); card.add(Box.createVerticalStrut(4));
        card.add(h); card.add(Box.createVerticalStrut(28));
        card.add(ul); card.add(Box.createVerticalStrut(6));
        card.add(userF); card.add(Box.createVerticalStrut(18));
        card.add(pl); card.add(Box.createVerticalStrut(6));
        card.add(passF); card.add(Box.createVerticalStrut(4));
        card.add(forgotBtn); card.add(Box.createVerticalStrut(16));
        card.add(loginBtn); card.add(Box.createVerticalStrut(16));
        card.add(signupRow);

        outer.add(card);
        return outer;
    }

    private void doLogin() {
        String u=userF.getText().trim();
        String p=new String(passF.getPassword());
        if (u.isEmpty()||p.isEmpty()) {
            JOptionPane.showMessageDialog(this,"Enter username and password.","Missing",JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (DAO.login(u,p)) {
            dispose(); new DashboardFrame(u).setVisible(true);
        } else {
            JOptionPane.showMessageDialog(this,"Wrong username or password.","Failed",JOptionPane.ERROR_MESSAGE);
            passF.setText("");
        }
    }
}

// ============================================================
//  DASHBOARD — all data calls now pass username for isolation
// ============================================================
class DashboardFrame extends JFrame {

    private final String username;

    private JTextField    sidF, fnF, lnF, emailF;
    private JComboBox<String> courseBox;
    private JComboBox<String> genderBox, yearBox, statusBox, positionBox;

    private QRPreview qrPreview;

    private DefaultTableModel tableModel;
    private JTable            table;
    private JTextField        searchF;

    private JLabel totalLbl, activeLbl, inactiveLbl;

    private int    selId       = -1;
    private String selPosition = "Member";
    private String qrPath      = "";

    DashboardFrame(String username) {
        this.username = username;
        setTitle("Student Org System  —  [" + username + "]");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1450, 860);
        setMinimumSize(new java.awt.Dimension(1200, 720));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { logout(); }
        });
        buildUI(); refresh();
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Col.LIGHT);
        root.add(buildTopBar(),  BorderLayout.NORTH);
        root.add(buildSidebar(), BorderLayout.WEST);
        root.add(buildMain(),    BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildTopBar() {
        JPanel bar=new JPanel(new BorderLayout());
        bar.setBackground(Col.NAVY);
        bar.setPreferredSize(new java.awt.Dimension(0,58));
        bar.setBorder(new EmptyBorder(0,24,0,24));

        JLabel title=UI.lbl("Student Organization — Membership System",
                             16,Font.BOLD,Col.WHITE);

        JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT,12,0));
        right.setOpaque(false);

        // ── Pass username to AllHistoryDialog ────────────────
        JButton histBtn = UI.btn("History Log", Col.TEAL, 12, 110, 36);
        histBtn.addActionListener(e -> new AllHistoryDialog(this, username).setVisible(true));

        JLabel userLbl=UI.lbl("  "+username,13,Font.PLAIN,new Color(180,200,255));
        JButton outBtn=UI.btn("Logout",Col.RED,12,90,34);
        outBtn.addActionListener(e->logout());

        right.add(histBtn); right.add(userLbl); right.add(outBtn);
        bar.add(title,BorderLayout.WEST);
        bar.add(right,BorderLayout.EAST);
        return bar;
    }

    private JPanel buildSidebar() {
        JPanel sb=new JPanel();
        sb.setLayout(new BoxLayout(sb,BoxLayout.Y_AXIS));
        sb.setBackground(Col.NAVY);
        sb.setPreferredSize(new java.awt.Dimension(210,0));
        sb.setBorder(new EmptyBorder(28,0,28,0));

        sb.add(sHead("Statistics"));
        totalLbl    = sLbl("Total Members","0",Col.STAT_BLUE);
        activeLbl   = sLbl("Active","0",Col.GREEN);
        inactiveLbl = sLbl("Inactive","0",Col.RED);
        sb.add(wStat(totalLbl,Col.STAT_BLUE));
        sb.add(wStat(activeLbl,Col.GREEN));
        sb.add(wStat(inactiveLbl,Col.RED));

        sb.add(Box.createVerticalStrut(20));
        sb.add(sHead("Positions"));

        for (String pos : new String[]{"President","Vice President","Secretary","Treasurer"}) {
            JLabel pl=new JLabel(pos);
            pl.setFont(new Font("Segoe UI",Font.PLAIN,11));
            pl.setForeground(new Color(160,185,225));
            pl.setBorder(new EmptyBorder(3,20,3,20));
            pl.setAlignmentX(LEFT_ALIGNMENT);
            sb.add(pl);
        }

        sb.add(Box.createVerticalStrut(16));
        sb.add(sHead("Tips"));
        for (String tip : new String[]{
                "Scan QR to fill form",
                "Click row to select",
                "View member history",
                "Set position on Add"}) {
            JLabel t=new JLabel("<html><body style='width:155px'>"+tip+"</body></html>");
            t.setFont(new Font("Segoe UI",Font.PLAIN,11));
            t.setForeground(new Color(150,175,220));
            t.setBorder(new EmptyBorder(4,20,4,20));
            t.setAlignmentX(LEFT_ALIGNMENT);
            sb.add(t);
        }
        return sb;
    }

    private JLabel sHead(String t) {
        JLabel l=new JLabel(t);
        l.setFont(new Font("Segoe UI",Font.BOLD,11));
        l.setForeground(new Color(130,160,220));
        l.setBorder(new EmptyBorder(0,20,8,20));
        l.setAlignmentX(LEFT_ALIGNMENT); return l;
    }
    private JLabel sLbl(String label,String val,Color accent) {
        JLabel l=new JLabel(sHtml(label,val));
        l.putClientProperty("label",label); l.putClientProperty("accent",accent);
        return l;
    }
    private String sHtml(String lbl,String val) {
        return "<html><span style='font-size:22px;color:#fff'><b>"+val+"</b></span>"
             + "<br><span style='font-size:10px;color:#8ab'>"+lbl+"</span></html>";
    }
    private void rStat(JLabel l,int v){
        l.setText(sHtml((String)l.getClientProperty("label"),String.valueOf(v)));
    }
    private JPanel wStat(JLabel l,Color ac) {
        JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)){
            @Override protected void paintComponent(Graphics g){
                super.paintComponent(g); g.setColor(ac);
                g.fillRoundRect(0,8,4,getHeight()-16,4,4);
            }
        };
        p.setOpaque(false); p.setBorder(new EmptyBorder(6,18,6,68));
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,68));
        p.add(l); return p;
    }

    private JPanel buildMain() {
        JPanel area=new JPanel(new BorderLayout(12,12));
        area.setBackground(Col.LIGHT);
        area.setBorder(new EmptyBorder(18,18,18,18));
        area.add(buildForm(),  BorderLayout.NORTH);
        area.add(buildTable(), BorderLayout.CENTER);
        return area;
    }

    private JPanel buildForm() {
        JPanel card=new JPanel(new BorderLayout());
        card.setBackground(Col.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(225,228,240),1,true),
            new EmptyBorder(18,22,16,22)));

        JLabel hdr=UI.lbl("Member Information",15,Font.BOLD,Col.NAVY);
        hdr.setBorder(new EmptyBorder(0,0,14,0));

        sidF    = new JTextField(); UI.styleField(sidF);
        fnF     = new JTextField(); UI.styleField(fnF);
        lnF     = new JTextField(); UI.styleField(lnF);
        courseBox = new JComboBox<>(new String[]{
            "BS Nursing (BSN)",
            "BS Midwifery (BSM)",
            "BS Accountancy (BSA)",
            "BS Business Administration - Financial Management",
            "BS Business Administration - Human Resource Management",
            "BS Business Administration - Marketing Management",
            "BS Customs Administration (BSCA)",
            "BS Computer Science (BSCS)",
            "BS Information Technology (BSIT)",
            "BS Entertainment and Multimedia Computing - Digital Animation Technology",
            "BS Entertainment and Multimedia Computing - Game Development",
            "BA Communication (BACOMM)",
            "Bachelor of Early Childhood Education (BECEd)",
            "Bachelor of Culture and Arts Education",
            "Bachelor of Physical Education (BPEd)",
            "Bachelor of Elementary Education (BEEd)",
            "BS Secondary Education - English",
            "BS Secondary Education - Filipino",
            "BS Secondary Education - Mathematics",
            "BS Secondary Education - Science",
            "BS Secondary Education - Social Studies",
            "BS Hospitality Management (BSHM)",
            "BS Tourism Management (BSTM)"
        });
        courseBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        courseBox.setBackground(new Color(250,251,254));
        emailF  = new JTextField(); UI.styleField(emailF);
        genderBox   = UI.combo("Male","Female","Other");
        yearBox     = UI.combo("1","2","3","4","5");
        statusBox   = UI.combo("Active","Inactive");
        positionBox = UI.combo(Const.POSITIONS);
        positionBox.setPreferredSize(new java.awt.Dimension(200,34));

        sidF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){refreshQR();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){refreshQR();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){refreshQR();}
        });

        JPanel grid=new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints g=new GridBagConstraints();
        g.fill=GridBagConstraints.HORIZONTAL; g.insets=new Insets(4,6,4,6);

        place(grid,g,0,0,"Student ID *",  sidF);
        place(grid,g,2,0,"First Name *",  fnF);
        place(grid,g,4,0,"Last Name *",   lnF);

        place(grid,g,0,1,"Course *",      courseBox);
        place(grid,g,2,1,"Year Level",    yearBox);
        place(grid,g,4,1,"Gender",        genderBox);

        g.gridx=0; g.gridy=2; g.weightx=0.15; grid.add(fLbl("Position"),g);
        g.gridx=1; g.weightx=0.35; g.gridwidth=1; grid.add(positionBox,g);
        g.gridwidth=1;

        g.gridx=2; g.gridy=2; g.weightx=0.15; grid.add(fLbl("Status"),g);
        g.gridx=3; g.weightx=0.35; grid.add(statusBox,g);

        g.gridx=0; g.gridy=3; g.weightx=0.15; grid.add(fLbl("Email"),g);
        g.gridx=1; g.weightx=0.85; g.gridwidth=5; grid.add(emailF,g);
        g.gridwidth=1;

        qrPreview=new QRPreview();
        JPanel qrWrap=new JPanel(new BorderLayout(0,6));
        qrWrap.setOpaque(false);
        JLabel qrLbl=UI.lbl("QR Preview",11,Font.BOLD,Col.GREY);
        qrLbl.setHorizontalAlignment(SwingConstants.CENTER);
        qrWrap.add(qrLbl,BorderLayout.NORTH);
        qrWrap.add(qrPreview,BorderLayout.CENTER);

        JPanel formRow=new JPanel(new BorderLayout(14,0));
        formRow.setOpaque(false);
        formRow.add(grid,   BorderLayout.CENTER);
        formRow.add(qrWrap, BorderLayout.EAST);

        JPanel btns=new JPanel(new FlowLayout(FlowLayout.LEFT,10,8));
        btns.setOpaque(false);

        JButton addBtn  = UI.btn("Add",      Col.GREEN);
        JButton updBtn  = UI.btn("Update",   Col.BLUE);
        JButton delBtn  = UI.btn("Delete",   Col.RED);
        JButton clrBtn  = UI.btn("Clear",    new Color(148,163,184));
        JButton scanBtn = UI.btn("Scan QR",  Col.ORANGE);
        JButton showQR  = UI.btn("Show QR",  Col.PURPLE);
        JButton histBtn = UI.btn("History",  Col.TEAL);

        addBtn.addActionListener(e  -> doAdd());
        updBtn.addActionListener(e  -> doUpdate());
        delBtn.addActionListener(e  -> doDelete());
        clrBtn.addActionListener(e  -> clearForm());
        scanBtn.addActionListener(e -> openScanner());
        showQR.addActionListener(e  -> showQR());
        histBtn.addActionListener(e -> showHistory());

        btns.add(addBtn); btns.add(updBtn); btns.add(delBtn); btns.add(clrBtn);
        btns.add(scanBtn); btns.add(showQR); btns.add(histBtn);

        card.add(hdr,     BorderLayout.NORTH);
        card.add(formRow, BorderLayout.CENTER);
        card.add(btns,    BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildTable() {
        JPanel card=new JPanel(new BorderLayout());
        card.setBackground(Col.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(225,228,240),1,true),
            new EmptyBorder(16,18,16,18)));

        JPanel topRow=new JPanel(new BorderLayout(10,0));
        topRow.setOpaque(false); topRow.setBorder(new EmptyBorder(0,0,12,0));
        JLabel tblTitle=UI.lbl("Member List",15,Font.BOLD,Col.NAVY);

        searchF=new JTextField(22); UI.styleField(searchF);
        searchF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){doSearch();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){doSearch();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){doSearch();}
        });
        JPanel sw=new JPanel(new BorderLayout()); sw.setOpaque(false);
        sw.add(UI.lbl("Search:  ",13,Font.PLAIN,Col.DARK),BorderLayout.WEST);
        sw.add(searchF,BorderLayout.CENTER);
        topRow.add(tblTitle,BorderLayout.WEST);
        topRow.add(sw,BorderLayout.EAST);

        String[] cols={"ID","Student ID","First Name","Last Name",
                        "Gender","Course","Year","Email","Status","Position"};
        tableModel=new DefaultTableModel(cols,0){
            @Override public boolean isCellEditable(int r,int c){return false;}
        };

        table=new JTable(tableModel);
        table.setFont(new Font("Segoe UI",Font.PLAIN,13));
        table.setRowHeight(30);
        table.setGridColor(new Color(235,238,248));
        table.setShowHorizontalLines(true); table.setShowVerticalLines(false);
        table.setSelectionBackground(Col.SEL_BG); table.setSelectionForeground(Col.NAVY);
        table.setIntercellSpacing(new java.awt.Dimension(10,3));
        JTableHeader header=table.getTableHeader();
        header.setFont(new Font("Segoe UI",Font.BOLD,12));
        header.setBackground(new Color(240,244,255));
        header.setForeground(Col.NAVY);

        int[] widths={40,110,90,90,60,165,42,155,68,145};
        for (int i=0;i<widths.length;i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer(){
            @Override public Component getTableCellRendererComponent(
                    JTable t,Object v,boolean sel,boolean foc,int row,int col){
                super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                setBorder(new EmptyBorder(0,8,0,8));
                if (!sel) {
                    setBackground(row%2==0?Col.WHITE:Col.ROW_ALT);
                    setFont(new Font("Segoe UI",Font.PLAIN,13));
                    if (col==8 && v!=null) {
                        setForeground("Active".equals(v.toString())
                            ? new Color(22,163,74) : new Color(185,28,28));
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (col==9 && v!=null) {
                        switch(v.toString()) {
                            case "President":
                                setForeground(new Color(150,90,0)); setFont(getFont().deriveFont(Font.BOLD)); break;
                            case "Vice President":
                                setForeground(new Color(20,60,180)); setFont(getFont().deriveFont(Font.BOLD)); break;
                            case "Secretary": case "Treasurer":
                                setForeground(new Color(13,148,136)); setFont(getFont().deriveFont(Font.BOLD)); break;
                            default: setForeground(Col.DARK);
                        }
                    } else { setForeground(Col.DARK); }
                }
                return this;
            }
        });

        table.getSelectionModel().addListSelectionListener(e->{
            if (!e.getValueIsAdjusting() && table.getSelectedRow()>=0)
                fillFromRow(table.getSelectedRow());
        });

        JScrollPane scroll=new JScrollPane(table);
        scroll.setBorder(new LineBorder(new Color(225,228,245),1));
        scroll.getViewport().setBackground(Col.WHITE);

        card.add(topRow,BorderLayout.NORTH);
        card.add(scroll,BorderLayout.CENTER);
        return card;
    }

    private void doAdd() {
        Member m = readForm(); if (m==null) return;
        m.qrPath = QRGen.save(m); qrPath = m.qrPath;
        if (DAO.add(m, username)) { showOk("Member added!\nPosition: " + m.position + "\nQR saved."); refresh(); }
        else showErr("Failed to add. Student ID might already exist.");
    }

    private void doUpdate() {
        if (selId<0) { showErr("Select a member from the table first."); return; }
        Member m = readForm(); if (m==null) return;
        m.id = selId;
        m.qrPath = QRGen.save(m); qrPath = m.qrPath;
        if (DAO.update(m, selPosition, username)) { showOk("Member updated!\nPosition: " + m.position); refresh(); selectById(selId); }
        else showErr("Failed to update.");
    }

    private void doDelete() {
        if (selId<0) { showErr("Select a member first."); return; }
        int r=JOptionPane.showConfirmDialog(this,
            "Delete this member and all their history?\nThis cannot be undone.",
            "Confirm Delete",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
        if (r==JOptionPane.YES_OPTION) {
            if (!qrPath.isEmpty()) new File(qrPath).delete();
            // ── Pass username so only owner can delete ────────
            if (DAO.delete(selId, username)) { showOk("Member deleted."); clearForm(); refresh(); }
            else showErr("Delete failed.");
        }
    }

    private void doSearch() {
        String kw=searchF.getText().trim();
        // ── Pass username to scope search results ─────────────
        loadTable(kw.isEmpty() ? DAO.getAll(username) : DAO.search(kw, username));
    }

    private void openScanner() {
        new QRScanDialog(this, scanned -> {
            autoFill(scanned);
            new Thread(()->{
                try{
                    SwingUtilities.invokeLater(()->fnF.setBackground(new Color(220,255,220)));
                    Thread.sleep(900);
                    SwingUtilities.invokeLater(()->fnF.setBackground(new Color(250,251,254)));
                } catch(InterruptedException ignored){}
            }).start();
            JOptionPane.showMessageDialog(this,
                "QR Scanned!\n\n" +
                "Student ID : " + scanned.studentId + "\n" +
                "Name       : " + scanned.firstName + " " + scanned.lastName + "\n" +
                "Course     : " + scanned.course + " (Year " + scanned.yearLevel + ")\n" +
                "Position   : " + scanned.position + "\n\n" +
                "Click ADD to register this member.",
                "QR Scan Complete", JOptionPane.INFORMATION_MESSAGE);
        }).setVisible(true);
    }

    private void autoFill(Member m) {
        sidF.setText(m.studentId != null ? m.studentId : "");
        fnF.setText (m.firstName != null ? m.firstName : "");
        lnF.setText (m.lastName  != null ? m.lastName  : "");
        if (m.course != null) {
            for (int i = 0; i < courseBox.getItemCount(); i++) {
                if (courseBox.getItemAt(i).equalsIgnoreCase(m.course)) {
                    courseBox.setSelectedIndex(i); break;
                }
            }
        }
        yearBox.setSelectedItem(String.valueOf(m.yearLevel));
        statusBox.setSelectedItem(m.status != null ? m.status : "Active");
        if (m.position != null) positionBox.setSelectedItem(m.position);
        refreshQR();
    }

    private void showQR() {
        if (selId<0) { showErr("Select a member first."); return; }
        if (qrPath.isEmpty() || !new File(qrPath).exists()) {
            showErr("No QR image found. Update the member to regenerate."); return;
        }
        try {
            BufferedImage img=ImageIO.read(new File(qrPath));
            ImageIcon icon=new ImageIcon(img.getScaledInstance(280,280,Image.SCALE_SMOOTH));
            JOptionPane.showMessageDialog(this,new JLabel(icon),
                "QR — "+sidF.getText(), JOptionPane.PLAIN_MESSAGE);
        } catch(Exception e){ showErr("Cannot open QR: "+e.getMessage()); }
    }

    private void showHistory() {
        if (selId<0) { showErr("Select a member first to view their history."); return; }
        List<HistoryEntry> hist = DAO.getHistory(selId);
        String name = fnF.getText()+" "+lnF.getText();
        if (hist.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No history found for this member yet.\nHistory is recorded when position changes.",
                "No History", JOptionPane.INFORMATION_MESSAGE);
        } else {
            new HistoryDialog(this, selId, name, hist).setVisible(true);
        }
    }

    private Member readForm() {
        String sid = sidF.getText().trim();
        String fn  = fnF.getText().trim();
        String ln  = lnF.getText().trim();
        String co  = (String) courseBox.getSelectedItem();
        if (sid.isEmpty()) { showErr("Student ID is required."); return null; }
        if (fn.isEmpty())  { showErr("First Name is required."); return null; }
        if (ln.isEmpty())  { showErr("Last Name is required.");  return null; }
        if (co == null || co.isEmpty()) { showErr("Course is required."); return null; }
        if (sid.contains("|")) { showErr("Student ID cannot contain '|'."); return null; }
        Member m = new Member();
        m.studentId = sid; m.firstName = fn; m.lastName = ln;
        m.gender    = (String)genderBox.getSelectedItem();
        m.course    = co;
        m.yearLevel = Integer.parseInt((String)yearBox.getSelectedItem());
        m.email     = emailF.getText().trim();
        m.status    = (String)statusBox.getSelectedItem();
        m.position  = (String)positionBox.getSelectedItem();
        m.qrPath    = qrPath;
        return m;
    }

    private void fillFromRow(int row) {
        selId       = (int) tableModel.getValueAt(row,0);
        selPosition = (String) tableModel.getValueAt(row,9);
        sidF.setText   ((String)tableModel.getValueAt(row,1));
        fnF.setText    ((String)tableModel.getValueAt(row,2));
        lnF.setText    ((String)tableModel.getValueAt(row,3));
        genderBox.setSelectedItem(    tableModel.getValueAt(row,4));
        courseBox.setSelectedItem(tableModel.getValueAt(row,5));
        yearBox.setSelectedItem(String.valueOf(tableModel.getValueAt(row,6)));
        emailF.setText ((String)tableModel.getValueAt(row,7));
        statusBox.setSelectedItem(    tableModel.getValueAt(row,8));
        positionBox.setSelectedItem(  tableModel.getValueAt(row,9));
        // ── Only fetch own member's QR path ──────────────────
        DAO.getAll(username).stream().filter(m->m.id==selId).findFirst()
           .ifPresent(m->qrPath=m.qrPath!=null?m.qrPath:"");
        refreshQR();
    }

    private void selectById(int id) {
        for (int i=0;i<tableModel.getRowCount();i++) {
            if ((int)tableModel.getValueAt(i,0)==id) {
                table.setRowSelectionInterval(i,i);
                table.scrollRectToVisible(table.getCellRect(i,0,true));
                break;
            }
        }
    }

    private void clearForm() {
        selId=-1; selPosition="Member"; qrPath="";
        sidF.setText(""); fnF.setText(""); lnF.setText("");
        courseBox.setSelectedIndex(0); emailF.setText("");
        genderBox.setSelectedIndex(0); yearBox.setSelectedIndex(0);
        statusBox.setSelectedIndex(0); positionBox.setSelectedIndex(0);
        table.clearSelection(); qrPreview.reset();
    }

    private void refreshQR() {
        String sid = sidF.getText().trim();
        String fn  = fnF.getText().trim();
        String co  = (String) courseBox.getSelectedItem();
        if (!sid.isEmpty() && !fn.isEmpty() && !co.isEmpty()) {
            qrPreview.show(sid+"|"+fn+"|"+lnF.getText().trim()+"|"+co+"|"
                +yearBox.getSelectedItem()+"|"+statusBox.getSelectedItem()+"|"
                +positionBox.getSelectedItem());
        } else { qrPreview.reset(); }
    }

    private void loadTable(List<Member> list) {
        tableModel.setRowCount(0);
        for (Member m : list) {
            tableModel.addRow(new Object[]{
                m.id, m.studentId, m.firstName, m.lastName, m.gender,
                m.course, m.yearLevel, m.email, m.status, m.position
            });
        }
    }

    private void refresh() {
        // ── All stats and data scoped to logged-in user ───────
        loadTable(DAO.getAll(username));
        int total  = DAO.count(null, username);
        int active = DAO.count("status='Active'", username);
        rStat(totalLbl,total); rStat(activeLbl,active); rStat(inactiveLbl,total-active);
    }

    private void place(JPanel p,GridBagConstraints g,int col,int row,String lbl,JComponent f) {
        g.gridx=col;   g.gridy=row; g.weightx=0.2; p.add(fLbl(lbl),g);
        g.gridx=col+1; g.weightx=0.8; p.add(f,g);
    }
    private JLabel fLbl(String t) {
        JLabel l=new JLabel(t);
        l.setFont(new Font("Segoe UI",Font.PLAIN,12));
        l.setForeground(Col.DARK); return l;
    }
    private void logout() {
        int r=JOptionPane.showConfirmDialog(this,"Logout?","Logout",JOptionPane.YES_NO_OPTION);
        if (r==JOptionPane.YES_OPTION) { DB.close(); dispose(); new LoginFrame().setVisible(true); }
    }
    private void showOk(String m){ JOptionPane.showMessageDialog(this,m,"Success",JOptionPane.INFORMATION_MESSAGE); }
    private void showErr(String m){ JOptionPane.showMessageDialog(this,m,"Error",JOptionPane.ERROR_MESSAGE); }
}