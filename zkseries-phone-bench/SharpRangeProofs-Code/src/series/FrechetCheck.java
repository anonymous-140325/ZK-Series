package series;

import series.SeriesDist.LocalDistFunc;

import java.util.Arrays;
import java.util.Random;

/**
 * Validates SeriesDist.frechet against an exhaustive search over all couplings.
 *
 * The Go reference implements SUM, DTW, ERD and TWED but not the discrete
 * Frechet distance, so unlike its siblings this function cannot be checked by
 * diffing against Go (see SeriesDistCheck). It is instead checked two ways on
 * small inputs: against a recursive enumeration of every monotone coupling, and
 * against an independently written dynamic program. Both must agree with the
 * implementation, and d(X,X) must be zero.
 *
 *   java -cp build series.FrechetCheck
 */
public class FrechetCheck {
  static LocalDistFunc E=(a,b)->LocalDist.euclideanDistance(a,b);
  // brute force: min over all monotone couplings (1,1)->(n1,n2) of the max local distance
  static long brute(long[][] x,long[][] y){
    int n1=x.length,n2=y.length;
    long[][] best=new long[n2][n1];
    for(long[] r:best) Arrays.fill(r,Long.MAX_VALUE);
    // explicit DP over the same coupling set, computed independently of SeriesDist
    for(int j=0;j<n2;j++) for(int i=0;i<n1;i++){
      long c=E.apply(x[i],y[j]);
      if(i==0&&j==0){ best[0][0]=c; continue; }
      long m=Long.MAX_VALUE;
      if(i>0) m=Math.min(m,best[j][i-1]);
      if(j>0) m=Math.min(m,best[j-1][i]);
      if(i>0&&j>0) m=Math.min(m,best[j-1][i-1]);
      best[j][i]=Math.max(c,m);
    }
    return best[n2-1][n1-1];
  }
  // exhaustive enumeration of couplings, for very small inputs
  static long exhaustive(long[][] x,long[][] y){ return rec(x,y,0,0,0); }
  static long rec(long[][] x,long[][] y,int i,int j,long acc){
    long c=Math.max(acc,E.apply(x[i],y[j]));
    if(i==x.length-1&&j==y.length-1) return c;
    long best=Long.MAX_VALUE;
    if(i+1<x.length) best=Math.min(best,rec(x,y,i+1,j,c));
    if(j+1<y.length) best=Math.min(best,rec(x,y,i,j+1,c));
    if(i+1<x.length&&j+1<y.length) best=Math.min(best,rec(x,y,i+1,j+1,c));
    return best;
  }
  static long[][] gen(int T,int m,Random r){ long[][] s=new long[T][m];
    for(int t=0;t<T;t++) for(int k=0;k<m;k++) s[t][k]=r.nextInt(1000); return s; }
  public static void main(String[] a){
    Random r=new Random(31337); int ok=0,bad=0;
    for(int trial=0;trial<400;trial++){
      int T1=1+r.nextInt(6), T2=1+r.nextInt(6), m=1+r.nextInt(3);
      long[][] X=gen(T1,m,r), Y=gen(T2,m,r);
      long impl=SeriesDist.frechet(X,Y,0,E).distance;
      long ex=exhaustive(X,Y);
      long dp=brute(X,Y);
      if(impl==ex && impl==dp) ok++; else { bad++;
        if(bad<4) System.out.println("  MISMATCH T1="+T1+" T2="+T2+" m="+m+" impl="+impl+" exhaustive="+ex+" dp="+dp); }
    }
    // identical series must give 0
    long[][] Z=gen(9,3,r);
    long self=SeriesDist.frechet(Z,Z,0,E).distance;
    System.out.println("Frechet: matched exhaustive coupling search on "+ok+"/"+(ok+bad)+" random cases; d(X,X)="+self);
    if(bad>0||self!=0) System.exit(1);
  }
}
