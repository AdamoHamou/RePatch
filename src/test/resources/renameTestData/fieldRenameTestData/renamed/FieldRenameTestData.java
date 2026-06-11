package renameTestData.fieldRenameTestData;

// Renamed count to total
public class FieldRenameTestData {

    private int total = 0;

    private final String label = "data";

    public void increment() {
        total = total + 1;
    }

    public int report() {
        System.out.println(label + ": " + total);
        return total;
    }

}
