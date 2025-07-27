/**
 * Basic functionality validation for fastreturn keyword
 * Tests fundamental fastreturn behavior compared to regular return
 */
public class TestFastReturn {
    
    // Test 1: Basic linear recursion
    public static int linearRecursionReturn(int n) {
        if (n <= 0) {
            return 0;
        }
        return n + linearRecursionReturn(n - 1);
    }
    
    public static int linearRecursionFastReturn(int n) {
        if (n <= 0) {
            fastreturn 0;
        }
        fastreturn n + linearRecursionFastReturn(n - 1);
    }
    
    // Test 2: Factorial computation
    public static long factorialReturn(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * factorialReturn(n - 1);
    }
    
    public static long factorialFastReturn(int n) {
        if (n <= 1) {
            fastreturn 1;
        }
        fastreturn n * factorialFastReturn(n - 1);
    }
    
    // Test 3: Fibonacci sequence
    public static int fibonacciReturn(int n) {
        if (n <= 1) {
            return n;
        }
        return fibonacciReturn(n - 1) + fibonacciReturn(n - 2);
    }
    
    public static int fibonacciFastReturn(int n) {
        if (n <= 1) {
            fastreturn n;
        }
        fastreturn fibonacciFastReturn(n - 1) + fibonacciFastReturn(n - 2);
    }
    
    // Test 4: Tail recursion simulation
    public static int tailRecursionReturn(int n, int acc) {
        if (n == 0) {
            return acc;
        }
        return tailRecursionReturn(n - 1, acc + n);
    }
    
    public static int tailRecursionFastReturn(int n, int acc) {
        if (n == 0) {
            fastreturn acc;
        }
        fastreturn tailRecursionFastReturn(n - 1, acc + n);
    }
    
    public static void main(String[] args) {
        int[] testSizes = {100, 500, 1000, 2000};
        
        for (int size : testSizes) {
            System.out.println("Size: " + size);
            
            long startTime = System.nanoTime();
            int result1 = linearRecursionReturn(size);
            long returnTime = System.nanoTime() - startTime;
            
            startTime = System.nanoTime();
            int result2 = linearRecursionFastReturn(size);
            long fastReturnTime = System.nanoTime() - startTime;
            
            double improvement = ((double)(returnTime - fastReturnTime) / returnTime) * 100;
            System.out.printf("Linear: return=%dns, fastreturn=%dns, diff=%.1f%%\n", 
                returnTime, fastReturnTime, improvement);
            
            startTime = System.nanoTime();
            long fact1 = factorialReturn(size / 10);
            long factReturnTime = System.nanoTime() - startTime;
            
            startTime = System.nanoTime();
            long fact2 = factorialFastReturn(size / 10);
            long factFastReturnTime = System.nanoTime() - startTime;
            
            improvement = ((double)(factReturnTime - factFastReturnTime) / factReturnTime) * 100;
            System.out.printf("Factorial: return=%dns, fastreturn=%dns, diff=%.1f%%\n", 
                factReturnTime, factFastReturnTime, improvement);
            
            startTime = System.nanoTime();
            int tail1 = tailRecursionReturn(size, 0);
            long tailReturnTime = System.nanoTime() - startTime;
            
            startTime = System.nanoTime();
            int tail2 = tailRecursionFastReturn(size, 0);
            long tailFastReturnTime = System.nanoTime() - startTime;
            
            improvement = ((double)(tailReturnTime - tailFastReturnTime) / tailReturnTime) * 100;
            System.out.printf("Tail: return=%dns, fastreturn=%dns, diff=%.1f%%\n", 
                tailReturnTime, tailFastReturnTime, improvement);
            
            System.out.println();
        }
        
        System.out.println("Fibonacci size: 25");
        long startTime = System.nanoTime();
        int fib1 = fibonacciReturn(25);
        long fibReturnTime = System.nanoTime() - startTime;
        
        startTime = System.nanoTime();
        int fib2 = fibonacciFastReturn(25);
        long fibFastReturnTime = System.nanoTime() - startTime;
        
        double improvement = ((double)(fibReturnTime - fibFastReturnTime) / fibReturnTime) * 100;
        System.out.printf("Fibonacci: return=%dns, fastreturn=%dns, diff=%.1f%%\n", 
            fibReturnTime, fibFastReturnTime, improvement);
    }
}
