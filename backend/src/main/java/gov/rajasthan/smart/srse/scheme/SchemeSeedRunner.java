package gov.rajasthan.smart.srse.scheme;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Upserts the one scheme the codebase has real worked-example data for
 * (CLAUDE.md's Ekal Naari example) so the scheme picker isn't empty on first
 * boot. Officers add further schemes via {@link SchemeController#create}.
 */
@Component
public class SchemeSeedRunner implements ApplicationRunner {

    private final SchemeRepository repository;

    public SchemeSeedRunner(SchemeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.findByCode("EKAL_NAARI").isPresent()) {
            return;
        }
        repository.save(new Scheme(null, "EKAL_NAARI", "Ekal Naari Pension",
                "Divorced-woman pension scheme — age >= 18, income < Rs.48,000, domicile, "
                        + "with BPL/Antyodaya + Sahariya/Kathodi/Khairwa income exemption.",
                true, Instant.now()));
    }
}
