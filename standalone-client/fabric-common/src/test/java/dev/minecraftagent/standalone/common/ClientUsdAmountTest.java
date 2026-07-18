package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ClientUsdAmountTest {
  @Test
  void convertsUsdToExactMicroUsdAndBack() {
    assertEquals(0, ClientUsdAmount.parse("0", "/model/inputPriceUsd"));
    assertEquals(150_000, ClientUsdAmount.parse("0.15", "/model/inputPriceUsd"));
    assertEquals(600_000, ClientUsdAmount.parse(" 0.600000 ", "/model/outputPriceUsd"));
    assertEquals("0.15", ClientUsdAmount.format(150_000));
    assertEquals("0.6", ClientUsdAmount.format(600_000));
  }

  @Test
  void rejectsAmbiguousOrUnrepresentableAmountsWithTheField() {
    for (var value : new String[] {"", "-1", ".15", "0,15", "$0.15", "0.0000001"}) {
      var failure =
          assertThrows(
              ClientConfigurationException.class,
              () -> ClientUsdAmount.parse(value, "/model/inputPriceUsd"));
      assertEquals("USD_AMOUNT_INVALID", failure.code());
      assertEquals("/model/inputPriceUsd", failure.field());
    }
  }
}
