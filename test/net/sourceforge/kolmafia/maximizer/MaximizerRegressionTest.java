package net.sourceforge.kolmafia.maximizer;

import static internal.helpers.Maximizer.*;
import static internal.helpers.Player.withBjorned;
import static internal.helpers.Player.withEnthroned;
import static internal.helpers.Player.withEquippableItem;
import static internal.helpers.Player.withEquipped;
import static internal.helpers.Player.withFamiliarInTerrarium;
import static internal.helpers.Player.withSign;
import static internal.helpers.Player.withStats;
import static internal.matchers.Maximizer.recommendsSlot;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import internal.helpers.Cleanups;
import net.sourceforge.kolmafia.FamiliarData;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.equipment.Slot;
import net.sourceforge.kolmafia.modifiers.DerivedModifier;
import net.sourceforge.kolmafia.modifiers.DoubleModifier;
import net.sourceforge.kolmafia.objectpool.FamiliarPool;
import net.sourceforge.kolmafia.session.EquipmentManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MaximizerRegressionTest {
  @BeforeEach
  public void init() {
    KoLCharacter.reset(true);
  }

  // https://kolmafia.us/threads/maximizer-reduces-score-with-combat-chance-at-soft-limit-failing-test-included.25672/
  @Test
  public void maximizeShouldNotRemoveEquipmentThatCanNoLongerBeEquipped() {
    var cleanups =
        new Cleanups(
            // slippers have a Moxie requirement of 125
            withEquipped(Slot.ACCESSORY1, "Fuzzy Slippers of Hatred"),
            // get our Moxie below 125 (e.g. basic hot dogs, stat limiting effects)
            withStats(0, 0, 0));

    try (cleanups) {
      assertFalse(
          EquipmentManager.canEquip("Fuzzy Slippers of Hatred"),
          "Can still equip Fuzzy Slippers of Hatred");
      assertTrue(
          maximize(
              "-combat -hat -weapon -offhand -back -shirt -pants -familiar -acc1 -acc2 -acc3"));
      assertEquals(5, -modFor(DoubleModifier.COMBAT_RATE), 0.01, "Base score is 5");
      assertTrue(maximize("-combat"));
      assertEquals(
          5, -modFor(DoubleModifier.COMBAT_RATE), 0.01, "Maximizing should not reduce score");
    }
  }

  // https://kolmafia.us/threads/maximizer-reduces-score-with-combat-chance-at-soft-limit-failing-test-included.25672/
  @Test
  public void freshCharacterShouldNotRecommendEverythingWithCurrentScore() {
    var cleanups = new Cleanups(withSign("Platypus"));

    try (cleanups) {
      assertTrue(maximize("familiar weight"));
      assertEquals(5, modFor(DoubleModifier.FAMILIAR_WEIGHT), 0.01, "Base score is 5");
      // monorail buff should always be available, but should not improve familiar weight.
      // so are friars, but I don't know why and that might be a bug
      assertThat(getBoosts(), hasSize(1));
      Boost ar = getBoosts().get(0);
      assertThat(ar.getCmd(), equalTo(""));
    }
  }

  // Sample test for https://kolmafia.us/showthread.php?23648&p=151903#post151903.
  @Test
  public void noTieCanLeaveSlotsEmpty() {
    var cleanups = withEquippableItem("helmet turtle");

    try (cleanups) {
      assertTrue(maximize("mys -tie"));
      assertEquals(0, modFor(DerivedModifier.BUFFED_MUS), 0.01);
    }
  }

  // Tests for https://kolmafia.us/threads/26413
  @Test
  public void keepBjornInhabitantEvenWhenUseless() {
    var cleanups =
        new Cleanups(withEquippableItem("Buddy Bjorn"), withBjorned(FamiliarPool.HAPPY_MEDIUM));

    try (cleanups) {
      assertTrue(maximize("+25 bonus buddy bjorn -tie"));
      // Actually equipped the buddy bjorn with its current inhabitant.
      assertEquals(25, modFor(DoubleModifier.MEATDROP), 0.01);
    }
  }

  // Tests for https://kolmafia.us/threads/26413
  @Test
  public void actuallyEquipsBonusBjorn() {
    var cleanups =
        new Cleanups(
            withEquippableItem("Buddy Bjorn"),
            withBjorned(FamiliarPool.HAPPY_MEDIUM),
            // +10 to all attributes
            withFamiliarInTerrarium(FamiliarPool.DICE),
            // +7 Muscle
            withEquipped(Slot.CONTAINER, "barskin cloak"));

    try (cleanups) {
      assertTrue(maximize("mus -buddy-bjorn +25 bonus buddy bjorn -tie"));
      // Unequipped the barskin cloak
      assertEquals(0, modFor(DerivedModifier.BUFFED_MUS), 0.01);
      // Actually equipped the buddy bjorn
      assertEquals(25, modFor(DoubleModifier.MEATDROP), 0.01);
    }
  }

  @Test
  public void keepsBonusBjornUnchanged() {
    var cleanups =
        new Cleanups(
            withEquippableItem("Buddy Bjorn"),
            withEquippableItem("Crown of Thrones"),
            withBjorned(FamiliarPool.HAPPY_MEDIUM),
            // +10 to all attributes. Worse than current hat.
            withFamiliarInTerrarium(FamiliarPool.DICE),
            // +7 Muscle
            withEquipped(Slot.CONTAINER, "barskin cloak"),
            // +25 Muscle
            withEquipped(Slot.HAT, "wreath of laurels"));

    try (cleanups) {
      assertTrue(maximize("mus -buddy-bjorn +25 bonus buddy bjorn -tie"));
      // Unequipped the barskin cloak, still have wreath of laurels equipped.
      assertEquals(25, modFor(DerivedModifier.BUFFED_MUS), 0.01);
      // Actually equipped the buddy bjorn
      assertEquals(25, modFor(DoubleModifier.MEATDROP), 0.01);
    }
  }

  @Test
  public void equipEmptyBjornNoSlot() {
    var cleanups =
        new Cleanups(
            withEquipped(Slot.CONTAINER, "Buddy Bjorn"),
            withFamiliarInTerrarium(FamiliarPool.MOSQUITO),
            withBjorned(FamiliarData.NO_FAMILIAR));

    try (cleanups) {
      // Here, buddy-bjorn refers to the slot, while Buddy Bjorn refers to the item associated with
      // that slot.
      // We've earlier forced that slot to be empty, and here we're asking mafia not to fill it when
      // maximizing, but to still equip the bjorn.
      assertTrue(maximize("item, -buddy-bjorn, +equip Buddy Bjorn"));
    }
  }

  @Test
  public void equipEmptyCrownNoSlot() {
    var cleanups =
        new Cleanups(
            withEquipped(Slot.HAT, "Crown of Thrones"),
            withFamiliarInTerrarium(FamiliarPool.MOSQUITO),
            withEnthroned(FamiliarData.NO_FAMILIAR));

    try (cleanups) {
      assertTrue(maximize("item, -crown-of-thrones, +equip Crown of Thrones"));
    }
  }

  @Test
  public void keepBjornEquippedWithBonus() {
    var cleanups =
        new Cleanups(
            // Provide a helpful alternative to the bjorn.
            withEquippableItem("vampyric cloake"), withEquipped(Slot.CONTAINER, "Buddy Bjorn"));

    try (cleanups) {
      assertTrue(maximize("adventures, -buddy-bjorn, +25 bonus Buddy Bjorn"));
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.CONTAINER))));
    }
  }

  @Test
  public void keepBjornEquippedAndUnchangedWithCrownAndBonus() {
    var cleanups =
        new Cleanups(
            withEquippableItem("Crown of Thrones"),
            withEquippableItem("time helmet"),
            withFamiliarInTerrarium(FamiliarPool.DICE),
            withEquipped(Slot.CONTAINER, "Buddy Bjorn"));

    try (cleanups) {
      assertTrue(maximize("adventures, -buddy-bjorn, +25 bonus Buddy Bjorn"));

      assertThat(getBoosts(), hasItem(recommendsSlot(Slot.HAT, "time helmet")));
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.CONTAINER))));
    }
  }

  @Test
  public void bjornAndCrownCanBothBeEmpty() {
    var cleanups =
        new Cleanups(withEquippableItem("Crown of Thrones"), withEquippableItem("Buddy Bjorn"));

    try (cleanups) {
      assertTrue(maximize("+25 bonus Buddy Bjorn, +25 bonus Crown of Thrones"));

      assertThat(getBoosts(), hasItem(recommendsSlot(Slot.HAT, "Crown of Thrones")));
      assertThat(getBoosts(), hasItem(recommendsSlot(Slot.CONTAINER, "Buddy Bjorn")));
    }
  }

  // https://kolmafia.us/threads/27073/
  @Test
  public void noTiePrefersCurrentGear() {
    var cleanups =
        new Cleanups(
            // Drops items, but otherwise irrelevant.
            withEquippableItem("Camp Scout backpack"),
            // +1 mys, +2 mox; 7 mox required; Maximizer needs to recommend changes in order to
            // create a
            // speculation.
            withEquippableItem("basic meat fez"),
            // +7 mus; 75 mys required
            withEquipped(Slot.CONTAINER, "barskin cloak"));

    try (cleanups) {
      assertTrue(maximize("mys -tie"));
      assertThat(getBoosts(), hasItem(recommendsSlot(Slot.HAT, "basic meat fez")));
      // No back change recommended.
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.CONTAINER))));
      assertEquals(7, modFor(DerivedModifier.BUFFED_MUS), 0.01);
    }
  }

  // https://kolmafia.us/threads/maximizer-recommends-unequipping-weapon-with-smithsness-offhand.28600/
  @Test
  public void shouldntUnequipWeaponWithSmithsnessOffhand() {
    var cleanups =
        new Cleanups(
            withEquipped(Slot.WEAPON, "seal-clubbing club"), withEquippableItem("Half a Purse"));

    try (cleanups) {
      assertTrue(maximize("meat"));
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.WEAPON))));
    }
  }

  // https://kolmafia.us/threads/maximizer-suggests-equipping-a-watch-in-the-wrong-slot.30202/
  @Test
  void suggestsReplacingExistingWatch() {
    var cleanups =
        new Cleanups(
            withEquippableItem("Counterclockwise Watch"), // 10, watch
            withEquipped(Slot.ACCESSORY1, "Cincho de Mayo"), // 0, not a watch
            withEquipped(Slot.ACCESSORY2, "numberwang"), // 5, not a watch
            withEquipped(Slot.ACCESSORY3, "baywatch") // 7, a watch
            );

    try (cleanups) {
      assertTrue(maximize("adv,fites,-tie"));
      assertEquals(15, modFor(DoubleModifier.ADVENTURES), 0.01);
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.ACCESSORY1))));
      assertThat(getBoosts(), not(hasItem(recommendsSlot(Slot.ACCESSORY2))));
      assertThat(getBoosts(), hasItem(recommendsSlot(Slot.ACCESSORY3, "Counterclockwise Watch")));
    }
  }
}
