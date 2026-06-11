package renameTestData.fieldRenameTestData;


public class FieldRenameTestData {

    private int count = 0;

    private final String label = "data";

    public void increment() {
        count = count + 1;
    }

    public int report() {
        System.out.println(label + ": " + count);
        return count;
    }

}
