package droidefense.emulator.machine.base.struct.model;

public class AndroidRField {

    private static final int sizeOfIntInHalfBytes = 8;
    private static final int numberOfBitsInAHalfByte = 4;
    private static final int halfByte = 0x0F;
    private static final char[] hexDigits = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private String owner, name;
    private int value;

    public AndroidRField(String owner, String name, int value) {
        this.owner = owner;
        this.name = name;
        this.value = value;
    }

    private static String decToHex(int dec) {
        StringBuilder hexBuilder = new StringBuilder(sizeOfIntInHalfBytes);
        hexBuilder.setLength(sizeOfIntInHalfBytes);
        for (int i = sizeOfIntInHalfBytes - 1; i >= 0; --i) {
            int j = dec & halfByte;
            hexBuilder.setCharAt(i, hexDigits[j]);
            dec >>= numberOfBitsInAHalfByte;
        }
        return hexBuilder.toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public String reverseName() {
        return "@" + this.owner + "/" + this.name;
    }

    @Override
    public String toString() {
        return "AndroidRField{" +
                "name='" + name + '\'' +
                ", value='" + value + '\'' +
                ", owner='" + owner + '\'' +
                ", reversed='" + reverseName() + '\'' +
                '}';
    }

    public String getAssembledID() {
        return "@" + AndroidRField.decToHex(this.value);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}
