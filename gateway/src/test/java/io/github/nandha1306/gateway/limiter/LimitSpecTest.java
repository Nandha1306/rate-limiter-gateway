package io.github.nandha1306.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class LimitSpecTest {

	@Test
	void burstZeroMeansCapacityEqualsLimit() {
		assertThat(new LimitSpec(100, 60, 0).capacity()).isEqualTo(100);
	}

	@Test
	void burstOverridesCapacity() {
		LimitSpec spec = new LimitSpec(60, 60, 10);
		assertThat(spec.capacity()).isEqualTo(10);
		assertThat(spec.refillPerSecond()).isEqualTo(1.0);
	}

	@Test
	void rejectsInvalidValues() {
		assertThatIllegalArgumentException().isThrownBy(() -> new LimitSpec(0, 60, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> new LimitSpec(10, 0, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> new LimitSpec(10, 60, -1));
	}
}
