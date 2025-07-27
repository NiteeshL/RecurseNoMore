public class ExtremeFastReturnStressTest {
    
    public static long deepLinearReturn(int n) {
        if (n <= 0) return 0;
        return n + deepLinearReturn(n - 1);
    }
    
    public static long deepLinearFastReturn(int n) {
        if (n <= 0) fastreturn 0;
        fastreturn n + deepLinearFastReturn(n - 1);
    }
    
    public static long deepTailReturn(int n, long acc) {
        if (n <= 0) return acc;
        return deepTailReturn(n - 1, acc + n);
    }
    
    public static long deepTailFastReturn(int n, long acc) {
        if (n <= 0) fastreturn acc;
        fastreturn deepTailFastReturn(n - 1, acc + n);
    }
    
    public static int deepFibReturn(int n) {
        if (n <= 1) return n;
        return deepFibReturn(n - 1) + deepFibReturn(n - 2);
    }
    
    public static int deepFibFastReturn(int n) {
        if (n <= 1) fastreturn n;
        fastreturn deepFibFastReturn(n - 1) + deepFibFastReturn(n - 2);
    }
    
    public static void main(String[] args) {
        int[] depths = {5000, 10000, 15000, 20000, 25000, 30000};
        
        for (int depth : depths) {
            System.out.println("Depth: " + depth);
            
            try {
                // Linear test
                long start = System.nanoTime();
                long result1 = deepLinearReturn(depth);
                long returnTime = System.nanoTime() - start;
                
                start = System.nanoTime();
                long result2 = deepLinearFastReturn(depth);
                long fastReturnTime = System.nanoTime() - start;
                
                double diff = ((double)returnTime - fastReturnTime) / 1000000.0;
                double percent = ((double)(returnTime - fastReturnTime) / returnTime) * 100;
                System.out.printf("Linear: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                    returnTime/1000000, fastReturnTime/1000000, diff, percent);
                
                // Tail test
                start = System.nanoTime();
                long tail1 = deepTailReturn(depth, 0);
                long tailReturnTime = System.nanoTime() - start;
                
                start = System.nanoTime();
                long tail2 = deepTailFastReturn(depth, 0);
                long tailFastReturnTime = System.nanoTime() - start;
                
                diff = ((double)tailReturnTime - tailFastReturnTime) / 1000000.0;
                percent = ((double)(tailReturnTime - tailFastReturnTime) / tailReturnTime) * 100;
                System.out.printf("Tail: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                    tailReturnTime/1000000, tailFastReturnTime/1000000, diff, percent);
                
            } catch (StackOverflowError e) {
                System.out.println("StackOverflow at depth " + depth);
                break;
            }
            
            System.out.println();
        }
        
        // Fibonacci test
        int[] fibSizes = {35, 38, 40};
        for (int size : fibSizes) {
            System.out.println("Fibonacci size: " + size);
            
            long start = System.nanoTime();
            int fib1 = deepFibReturn(size);
            long fibReturnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            int fib2 = deepFibFastReturn(size);
            long fibFastReturnTime = System.nanoTime() - start;
            
            double diff = ((double)fibReturnTime - fibFastReturnTime) / 1000000.0;
            double percent = ((double)(fibReturnTime - fibFastReturnTime) / fibReturnTime) * 100;
            System.out.printf("Fibonacci: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                fibReturnTime/1000000, fibFastReturnTime/1000000, diff, percent);
            
            System.out.println();
        }
    }
}
