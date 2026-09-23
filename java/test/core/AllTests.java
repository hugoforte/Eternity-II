package core;

/**
 * Runs the whole suite.
 *
 *   javac -d out src/core/*.java test/core/*.java
 *   java -cp out core.AllTests
 *
 * Exits with status 1 if anything failed, so it can be wired into CI.
 */
public final class AllTests {

    public static void main(String[] args) {
        long start = System.nanoTime();

        System.out.println("================================================================");
        System.out.println(" Eternity II solver test suite");
        System.out.println("================================================================");

        SidesTest.run();
        PiecesTest.run();
        InstanceTest.run();
        GeneratorTest.run();
        ValidatorTest.run();
        SolverTest.run();
        SolverConfigTest.run();
        MrvSolverTest.run();
        FillOrderTest.run();
        ScanSolverTest.run();
        ScanVariationTest.run();
        EdgeSlippingTest.run();
        ProgressLogTest.run();
        ColourQuotaTest.run();
        RepairTest.run();
        RootScreenTest.run();
        PortfolioSearchTest.run();
        CrossValidationTest.run();

        long ms = (System.nanoTime() - start) / 1000000L;
        T.summary();
        System.out.println(" total time : " + ms + " ms");

        if (T.failures != 0) {
            System.out.println(" EXIT 1");
            System.exit(1);
        }
    }
}
