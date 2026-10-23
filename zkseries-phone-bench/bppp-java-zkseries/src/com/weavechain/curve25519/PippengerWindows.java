package com.weavechain.curve25519;

import java.util.Arrays;
import java.util.List;

/**
 * The library's Pippenger multiscalar multiplication (MulUtils.mulPippenger), split into
 * its independent windows so that they can be computed on different threads.
 *
 * Each step is that of curve25519-dalek's Pippenger, which the library ports:
 *   - window width w = 6, 7 or 8 for fewer than 500, fewer than 800, or more points;
 *   - signed radix-2^w digits of each scalar (Scalar.toRadix2w), ceil(256/w) of them,
 *     plus one for w = 8, each in [-2^(w-1), 2^(w-1)];
 *   - window j: 2^(w-1) buckets, digit d adds (or, if negative, subtracts) the point to
 *     bucket |d|; sum_d d * bucket_d by running sums (window());
 *   - result = sum_j 2^(w j) * window_j, by Horner's rule (combine()).
 * Windows share only read-only data (points, digits), so computing them concurrently gives
 * the same result as computing them in order.
 *
 * In this package because it uses package-private arithmetic of curve25519-elisabeth 0.1.5
 * (EdwardsPoint, ProjectiveNielsPoint, RistrettoElement.repr). Variable time, as is
 * MulUtils.mulPippenger, which the prover already used for these multiplications.
 */
public final class PippengerWindows {
    private final int width,count,bucketCount;
    private final ProjectiveNielsPoint[] points;
    private final byte[][] digits;
    public PippengerWindows(List<Scalar> scalars,List<RistrettoElement> input) {
        if(scalars.size()!=input.size()||input.isEmpty())throw new IllegalArgumentException("MSM shape");
        width=input.size()<500?6:input.size()<800?7:8;
        count=(256+width-1)/width+(width==8?1:0);bucketCount=1<<(width-1);
        points=new ProjectiveNielsPoint[input.size()];digits=new byte[input.size()][];
        for(int i=0;i<points.length;i++) {
            points[i]=input.get(i).repr.toProjectiveNiels();
            digits[i]=scalars.get(i).toRadix2w(width);
        }
    }
    public int windows(){return count;}
    public RistrettoElement window(int index) {
        if(index<0||index>=count)throw new IllegalArgumentException("window index");
        EdwardsPoint[] buckets=new EdwardsPoint[bucketCount];Arrays.fill(buckets,EdwardsPoint.IDENTITY);
        for(int i=0;i<points.length;i++) {
            int digit=digits[i][index];
            if(digit>0)buckets[digit-1]=buckets[digit-1].add(points[i]).toExtended();
            else if(digit<0)buckets[-digit-1]=buckets[-digit-1].subtract(points[i]).toExtended();
        }
        EdwardsPoint running=buckets[bucketCount-1],sum=running;
        for(int i=bucketCount-2;i>=0;i--){running=running.add(buckets[i]);sum=sum.add(running);}
        return new RistrettoElement(sum);
    }
    public RistrettoElement combine(RistrettoElement[] sums) {
        if(sums.length!=count)throw new IllegalArgumentException("window count");
        EdwardsPoint result=sums[count-1].repr;
        for(int i=count-2;i>=0;i--)result=result.multiplyByPow2(width).add(sums[i].repr);
        return new RistrettoElement(result);
    }
}
