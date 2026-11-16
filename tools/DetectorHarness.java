import app.judgelamp.JudgeDetector;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

/**
 * Runs the app's detector over raw RGBA frames from stdin, e.g.
 *   ffmpeg -i play.mp4 -vf scale=1212:540:flags=area -pix_fmt rgba -f rawvideo - |
 *   java -cp out DetectorHarness 1212 540 60.1116 assets scores.txt [padBytes]
 */
public class DetectorHarness {
    public static void main(String[] a) throws Exception {
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        double fps = Double.parseDouble(a[2]);
        String assets = a[3];
        int pad = a.length > 5 ? Integer.parseInt(a[5]) : 0; // fake row padding to exercise rowStride
        JudgeDetector.Template[] tpl = {
            JudgeDetector.Template.load(new FileInputStream(assets + "/great.tpl"), "GREAT", JudgeDetector.BIT_GREAT),
            JudgeDetector.Template.load(new FileInputStream(assets + "/good.tpl"), "GOOD", JudgeDetector.BIT_GOOD),
            JudgeDetector.Template.load(new FileInputStream(assets + "/miss.tpl"), "MISS", JudgeDetector.BIT_MISS),
        };
        JudgeDetector det = new JudgeDetector(tpl);
        if (a.length > 6) det.setSensitivity(Integer.parseInt(a[6]));
        int stride = w * 4 + pad;
        byte[] frame = new byte[w * 4];
        ByteBuffer buf = ByteBuffer.allocateDirect(stride * (h - 1) + w * 4);
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1 << 22));
        PrintWriter out = new PrintWriter(a[4]);
        app.judgelamp.TimingProbe probe = new app.judgelamp.TimingProbe();
        int[] lastFired = {-100, -100, -100, -100, -100};
        boolean[] strongNow = new boolean[1];
        long ns = 0;
        int n = 0;
        try {
            while (true) {
                for (int y = 0; y < h; y++) {
                    in.readFully(frame);
                    buf.position(y * stride);
                    buf.put(frame);
                }
                long t0 = System.nanoTime();
                probe.observe(buf, w, h, stride, 4);
                int fired = det.process(buf, w, h, stride, 4);
                ns += System.nanoTime() - t0;
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    if ((fired & bit) == 0) continue;
                    boolean onset = n - lastFired[bit] > 6;
                    lastFired[bit] = n;
                    if (!onset) continue;
                    String timing = bit == 4 ? "-" : (probe.wasLate(det.lastConfirmDelay(bit)) ? "LATE" : "EARLY");
                    System.out.printf("ONSET %d %s %s%n", n, bit == 1 ? "GREAT" : bit == 2 ? "GOOD" : "MISS", timing);
                }
                // columns: time, fired mask (1 GREAT, 2 GOOD, 4 MISS), then whole-word / half-word score per template
                out.printf("%.3f %d %.3f %.3f %.3f %.3f %.3f %.3f%n", n / fps, fired,
                        tpl[0].lastFull, tpl[0].lastHalf, tpl[1].lastFull, tpl[1].lastHalf,
                        tpl[2].lastFull, tpl[2].lastHalf);
                n++;
            }
        } catch (java.io.EOFException e) {
            // end of video
        }
        out.close();
        System.out.printf("frames=%d  avg %.3f ms/frame%n", n, ns / 1e6 / Math.max(1, n));
    }
}
