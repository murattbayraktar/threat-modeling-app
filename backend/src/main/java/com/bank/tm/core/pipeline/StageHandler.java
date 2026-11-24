package com.bank.tm.core.pipeline;

import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.Stage;

/**
 * Akışın tek bir adımı. Adımlar yeniden çalıştırılabilir olmalıdır: bekleme (kullanıcı/LLM) sonrası aynı adım
 * tekrar çağrılır ve kaldığı yerden devam eder.
 */
public interface StageHandler {

    Stage stage();

    StageResult run(Review review) throws Exception;
}
