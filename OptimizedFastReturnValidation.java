public class OptimizedFastReturnValidation {
    
    public static long linearReturn(int n) {
        if (n <= 0) return 0;
        return n + linearReturn(n - 1);
    }
    
    public static long linearFastReturn(int n) {
        if (n <= 0) fastreturn 0;
        fastreturn n + linearFastReturn(n - 1);
    }
    
    public static long factorialReturn(int n) {
        if (n <= 1) return 1;
        return n * factorialReturn(n - 1);
    }
    
    public static long factorialFastReturn(int n) {
        if (n <= 1) fastreturn 1;
        fastreturn n * factorialFastReturn(n - 1);
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
        int[] sizes = {1000, 2000, 5000, 8000, 10000, 15000, 20000};
        
        for (int size : sizes) {
            System.out.println("Size: " + size);
            
            // Linear test
            long start = System.nanoTime();
            long result1 = linearReturn(size);
            long returnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            long result2 = linearFastReturn(size);
            long fastReturnTime = System.nanoTime() - start;
            
            double diff = ((double)returnTime - fastReturnTime) / 1000.0;
            double percent = ((double)(returnTime - fastReturnTime) / returnTime) * 100;
            System.out.printf("Linear: return=%d µs, fastreturn=%d µs, diff=%.1f µs, %.1f%%\n", 
                returnTime/1000, fastReturnTime/1000, diff, percent);
            
            // Factorial test
            start = System.nanoTime();
            long fact1 = factorialReturn(Math.min(size/100, 20));
            long factReturnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            long fact2 = factorialFastReturn(Math.min(size/100, 20));
            long factFastReturnTime = System.nanoTime() - start;
            
            diff = ((double)factReturnTime - factFastReturnTime) / 1000.0;
            percent = ((double)(factReturnTime - factFastReturnTime) / factReturnTime) * 100;
            System.out.printf("Factorial: return=%d µs, fastreturn=%d µs, diff=%.1f µs, %.1f%%\n", 
                factReturnTime/1000, factFastReturnTime/1000, diff, percent);
            
            // Tail test
            start = System.nanoTime();
            long tail1 = tailReturn(size, 0);
            long tailReturnTime = System.nanoTime() - start;
            
            start = System.nanoTime();
            long tail2 = tailFastReturn(size, 0);
            long tailFastReturnTime = System.nanoTime() - start;
            
            diff = ((double)tailReturnTime - tailFastReturnTime) / 1000.0;
            percent = ((double)(tailReturnTime - tailFastReturnTime) / tailReturnTime) * 100;
            System.out.printf("Tail: return=%d µs, fastreturn=%d µs, diff=%.1f µs, %.1f%%\n", 
                tailReturnTime/1000, tailFastReturnTime/1000, diff, percent);
            
            System.out.println();
        }
    }
}
