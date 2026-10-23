package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Regresión del bloqueo de Corazón: solo diamante/netherite, nunca hierro o inferior. */
class SacrificeArmorTest {

    @Test
    void blocksDiamondAndNetheritePieces() {
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_HELMET));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_CHESTPLATE));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_LEGGINGS));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_BOOTS));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.NETHERITE_HELMET));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.NETHERITE_CHESTPLATE));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.NETHERITE_LEGGINGS));
        assertTrue(SacrificeListener.isHighArmorMaterial(Material.NETHERITE_BOOTS));
    }

    @Test
    void allowsIronAndLowerTiers() {
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.IRON_HELMET));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.IRON_CHESTPLATE));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.IRON_LEGGINGS));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.IRON_BOOTS));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.CHAINMAIL_CHESTPLATE));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.GOLDEN_CHESTPLATE));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.LEATHER_CHESTPLATE));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.TURTLE_HELMET));
    }

    @Test
    void ignoresAirAndNonArmorItems() {
        assertFalse(SacrificeListener.isHighArmorMaterial(null));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.AIR));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_SWORD));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.DIAMOND_BLOCK));
        assertFalse(SacrificeListener.isHighArmorMaterial(Material.ELYTRA));
    }
}
