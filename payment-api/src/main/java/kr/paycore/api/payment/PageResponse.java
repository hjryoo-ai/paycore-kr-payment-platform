package kr.paycore.api.payment;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Spring Data Page 를 그대로 노출하면 직렬화 형태가 버전에 묶인다. 우리가 통제하는 형태로 감싼다. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
