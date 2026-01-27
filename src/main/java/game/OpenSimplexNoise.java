
package game;
public class OpenSimplexNoise {
    private static final int PSIZE = 256;
    private final short[] perm = new short[PSIZE * 2];

    public OpenSimplexNoise(long seed) {
        short[] source = new short[PSIZE];
        for (short i = 0; i < PSIZE; i++)
            source[i] = i;
        java.util.Random rand = new java.util.Random(seed);
        for (int i = PSIZE - 1; i >= 0; i--) {
            int r = rand.nextInt(i + 1);
            short tmp = source[i];
            source[i] = source[r];
            source[r] = tmp;
        }
        for (int i = 0; i < PSIZE * 2; i++)
            perm[i] = source[i & (PSIZE - 1)];
    }

    // 2D OpenSimplex
    public double eval(double x, double y) {
        final double SQRT3 = 1.7320508075688772;
        final double F2 = 0.5 * (SQRT3 - 1.0);
        final double G2 = (3.0 - SQRT3) / 6.0;

        double s = (x + y) * F2;
        double xs = x + s, ys = y + s;
        int i = fastFloor(xs);
        int j = fastFloor(ys);
        double t = (i + j) * G2;
        double X0 = i - t, Y0 = j - t;
        double x0 = x - X0, y0 = y - Y0;

        int i1, j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & (PSIZE - 1), jj = j & (PSIZE - 1);
        double n0 = contrib(ii, jj, x0, y0);
        double n1 = contrib(ii + i1, jj + j1, x1, y1);
        double n2 = contrib(ii + 1, jj + 1, x2, y2);

        // scale to about [-1,1]
        return 70.0 * (n0 + n1 + n2);
    }

    private double contrib(int i, int j, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0)
            return 0.0;
        int gi = perm[i + perm[j]] % 12;
        double grad = grad2[gi][0] * x + grad2[gi][1] * y;
        return t * t * t * t * grad;
    }

    private static int fastFloor(double x) {
        return x > 0 ? (int) x : (int) x - 1;
    }

    private static final double[][] grad2 = {
            { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 },
            { 1, 0 }, { -1, 0 }, { 1, 0 }, { -1, 0 },
            { 0, 1 }, { 0, -1 }, { 0, 1 }, { 0, -1 }
    };
}
