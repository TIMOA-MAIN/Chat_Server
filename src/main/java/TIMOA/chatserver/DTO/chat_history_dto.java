// 2. ChatHistoryResponse DTO 추가 (새 파일)
package TIMOA.chatserver.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_history_dto {
    private List<chat_message_response_dto> messages;
    private boolean hasMore;
    private Long totalCount;
}
