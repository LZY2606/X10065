package org.simdjson;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

class VectorUtils {

    static final VectorSpecies<Integer> INT_SPECIES;
    static final VectorSpecies<Byte> BYTE_SPECIES;

    static {
        String species = System.getProperty("org.simdjson.species", "preferred");
        switch (species) {
            case "preferred" -> {
                BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
                INT_SPECIES = IntVector.SPECIES_PREFERRED;
                assertSupportForSpecies(BYTE_SPECIES);
                assertSupportForSpecies(INT_SPECIES);
            }
            case "512" -> {
                BYTE_SPECIES = ByteVector.SPECIES_512;
                INT_SPECIES = IntVector.SPECIES_512;
            }
            case "256" -> {
                BYTE_SPECIES = ByteVector.SPECIES_256;
                INT_SPECIES = IntVector.SPECIES_256;
            }
            default -> throw new IllegalArgumentException("Unsupported vector species: " + species);
        }
    }

    private static void assertSupportForSpecies(VectorSpecies<?> species) {
        if (species.vectorShape() != VectorShape.S_256_BIT && species.vectorShape() != VectorShape.S_512_BIT) {
            throw new IllegalArgumentException("Unsupported vector species: " + species);
        }
    }

    static ByteVector repeat(byte[] array) {
        return repeat(array, BYTE_SPECIES);
    }

    static ByteVector repeat(byte[] array, VectorSpecies<Byte> species) {
        byte[] result = new byte[species.vectorByteSize()];
        for (int dst = 0; dst < result.length; dst += array.length) {
            System.arraycopy(array, 0, result, dst, array.length);
        }
        return ByteVector.fromArray(species, result, 0);
    }
}
