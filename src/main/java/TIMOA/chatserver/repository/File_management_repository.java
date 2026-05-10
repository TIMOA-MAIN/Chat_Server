package TIMOA.chatserver.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Repository
public class File_management_repository {
    private final String FILE_DIR = "user_file/";

    public String upload_file(MultipartFile file) throws IOException {

        File directory = new File(FILE_DIR);
        if(!directory.exists()) {                       //폴더 찾기
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();                         //폴더 생성
        }

        String originalFileName = file.getOriginalFilename();
        String finalFileName = generateUniqueFileName(originalFileName);

        Path filePath = Paths.get(FILE_DIR + finalFileName);
        Files.write(filePath, file.getBytes());

        log.info("파일 저장 완료: 원본명={}, 저장명={}", originalFileName, finalFileName);

        return finalFileName;
    }

    public Resource download_file(String file_name) throws IOException {

        Path filePath = Paths.get(FILE_DIR + file_name);

        if(!Files.exists(filePath))
            throw new IOException("파일을 찾을 수 없습니다 : " + file_name);

        Resource resource = new FileSystemResource(filePath);

        // 파일이 읽을 수 있는지 확인
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("파일을 읽을 수 없습니다: " + file_name);
        }

        return resource;
    }

    public void delete_file(String fileName) throws IOException {
        Path filePath = Paths.get(FILE_DIR + fileName);

        if (!Files.exists(filePath)) {
            throw new IOException("파일을 찾을 수 없습니다: " + fileName);
        }

        try {
            Files.delete(filePath);
            log.info("파일 삭제 성공: {}", fileName);
        } catch (IOException e) {
            log.error("파일 삭제 실패: {}", e.getMessage());
            throw new IOException("파일 삭제에 실패했습니다: " + fileName);
        }
    }

    public String getContentType(String fileName) throws IOException {
        Path filePath = Paths.get(FILE_DIR + fileName);
        String contentType = Files.probeContentType(filePath);

        if(contentType==null)
            contentType = "application/octet-stream";

        return contentType;
    }

    private String generateUniqueFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            originalFileName = "unnamed_file";
        }

        // 파일명과 확장자 분리
        String nameWithoutExtension;
        String extension;

        int lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < originalFileName.length() - 1) {
            nameWithoutExtension = originalFileName.substring(0, lastDotIndex);
            extension = originalFileName.substring(lastDotIndex); // .포함
        } else {
            nameWithoutExtension = originalFileName;
            extension = "";
        }

        // 원본 파일명으로 시작
        String candidateFileName = originalFileName;
        Path candidatePath = Paths.get(FILE_DIR + candidateFileName);

        // 중복되지 않는 파일명을 찾을 때까지 반복
        int counter = 1;
        while (Files.exists(candidatePath)) {
            candidateFileName = nameWithoutExtension + "(" + counter + ")" + extension;
            candidatePath = Paths.get(FILE_DIR + candidateFileName);
            counter++;

            // 무한 루프 방지 (1000개 이상은 비정상)
            if (counter > 1000) {
                throw new RuntimeException("파일명 생성 실패: 너무 많은 중복 파일이 존재합니다.");
            }
        }

        return candidateFileName;
    }
}
