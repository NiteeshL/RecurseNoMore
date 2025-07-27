public class FastReturnSweetSpotTest {
    
    public static long linearReturn(int n) {
        if (n <= 0) return 0;
        return n + linearReturn(n - 1);
    }
    
    public static long linearFastReturn(int n) {
        if (n <= 0) fastreturn 0;
        fastreturn n + linearFastReturn(n - 1);
    }
    
    public static int fibReturn(int n) {
        if (n <= 1) return n;
        return fibReturn(n - 1) + fibReturn(n - 2);
    }
    
    public static int fibFastReturn(int n) {
        if (n <= 1) fastreturn n;
        fastreturn fibFastReturn(n - 1) + fibFastReturn(n - 2);
    }
    
    public static long tailReturn(int n, long acc) {
        if (n <= 0) return acc;
        return tailReturn(n - 1, acc + n);
    }
    
    public static long tailFastReturn(int n, long acc) {
        if (n <= 0) fastreturn acc;
        fastreturn tailFastReturn(n - 1, acc + n);
    }
    
    public static void main(String[] args) {
        // Linear recursion sweet spot test
        int[] linearSizes = {1000, 5000, 10000, 15000, 20000, 25000, 30000};
        System.out.println("Linear recursion:");
        for (int size : linearSizes) {
            long start = System.nanoTime();
            long result1 = linearReturn(size);
            long returnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            long result2 = linearFastReturn(size);
            long fastReturnTime = System.nanoTime() - start;
            
            double diff = ((double)returnTime - fastReturnTime) / 1000000.0;
            double percent = ((double)(returnTime - fastReturnTime) / returnTime) * 100;
            System.out.printf("Size %d: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                size, returnTime/1000000, fastReturnTime/1000000, diff, percent);
        }
        
        System.out.println();
        
        // Fibonacci sweet spot test
        int[] fibSizes = {25, 28, 30, 32, 35, 38};
        System.out.println("Fibonacci:");
        for (int size : fibSizes) {
            long start = System.nanoTime();
            int fib1 = fibReturn(size);
            long fibReturnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            int fib2 = fibFastReturn(size);
            long fibFastReturnTime = System.nanoTime() - start;
            
            double diff = ((double)fibReturnTime - fibFastReturnTime) / 1000000.0;
            double percent = ((double)(fibReturnTime - fibFastReturnTime) / fibReturnTime) * 100;
            System.out.printf("Size %d: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                size, fibReturnTime/1000000, fibFastReturnTime/1000000, diff, percent);
        }
        
        System.out.println();
        
        // Tail recursion sweet spot test
        int[] tailSizes = {2000, 8000, 15000, 25000};
        System.out.println("Tail recursion:");
        for (int size : tailSizes) {
            try {
                long start = System.nanoTime();
                long tail1 = tailReturn(size, 0);
                long tailReturnTime = System.nanoTime() - start;
                
                start = System.nanoTime();
                long tail2 = tailFastReturn(size, 0);
                long tailFastReturnTime = System.nanoTime() - start;
                
                double diff = ((double)tailReturnTime - tailFastReturnTime) / 1000000.0;
                double percent = ((double)(tailReturnTime - tailFastReturnTime) / tailReturnTime) * 100;
                System.out.printf("Size %d: return=%d ms, fastreturn=%d ms, diff=%.1f ms, %.1f%%\n", 
                    size, tailReturnTime/1000000, tailFastReturnTime/1000000, diff, percent);
            } catch (StackOverflowError e) {
                System.out.println("Size " + size + ": StackOverflow");
            }
        }
    }
}
