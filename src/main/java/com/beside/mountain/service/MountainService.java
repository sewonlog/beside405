package com.beside.mountain.service;

import com.beside.mountain.domain.MntiEntity;
import com.beside.mountain.dto.Course;
import com.beside.mountain.dto.CourseResponse;
import com.beside.mountain.dto.MntiDetailOutput;
import com.beside.mountain.dto.MntiListOutput;
import com.beside.mountain.repository.MntiRepository;
import com.beside.util.CommonUtil;
import com.beside.util.Coordinate;
import com.beside.weather.api.WeatherApi;
import com.beside.weather.dto.Weather;
import com.beside.weather.dto.WeatherResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.servlet.error.DefaultErrorViewResolver;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MountainService {

    private final MntiRepository mntiRepository;
    private final WeatherApi weatherApi;
    private final ObjectMapper objectMapper;

    private final Map<String, String> courseMap = new HashMap<>();

    @Getter
    private List<WeatherResponse> weatherList;

    @PostConstruct
    public void init() {
        try {
            initializeCourseMap();
            updateWeatherList();
        } catch (IOException | URISyntaxException e) {
            // 로깅 및 예외 처리
            e.printStackTrace();
        }
    }


    private void initializeCourseMap() throws IOException {
        // 모든 산 목록을 가져와서 courseMap을 초기화
        List<MntiEntity> mountains = mntiRepository.findAll();
        for (MntiEntity mntiEntity : mountains) {
            ClassPathResource resource = new ClassPathResource("/mntiCourseData/PMNTN_" + mntiEntity.getMntiName() + "_" + mntiEntity.getMntiListNo() + ".json");
            JsonNode rootNode = objectMapper.readTree(resource.getContentAsByteArray());
            JsonNode itemsNode = rootNode.path("features");

            if (itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    String courseName = item.path("attributes").path("PMNTN_NM").asText();
                    String courseNo = item.path("attributes").path("PMNTN_SN").asText();
                    long courseTime =item.path("attributes").path("PMNTN_UPPL").asLong() + item.path("attributes").path("PMNTN_GODN").asLong();
                    if (courseName != null && !courseName.isEmpty() && !courseName.equals(" ")) {
                        if(courseTime >= 10) {
                            courseMap.put(courseNo, courseName);
                        }
                    }
                }
            }
        }
    }



    public List<MntiListOutput> getList(String keyword) throws IOException {
        return fetchAndConvert(keyword);
    }

    public Page<MntiListOutput> getPageList(Pageable pageable, String keyword) throws IOException {
        List<MntiListOutput> mntiListOutput = fetchAndConvert(keyword);

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), mntiListOutput.size());
        return new PageImpl<>(mntiListOutput.subList(start, end), pageable, mntiListOutput.size());
    }

    @Cacheable(value = "mntiListCache", key = "#keyword")
    public List<MntiListOutput> fetchAndConvert(String keyword) throws IOException {
        List<MntiEntity> list;

        if (StringUtils.hasText(keyword)) {
            list = mntiRepository.findByMntiNameContaining(keyword);
        } else {
            list = mntiRepository.findAll();
        }

        List<MntiListOutput> mntiListOutput = new ArrayList<>();
        for (MntiEntity mntiEntity : list) {
            MntiListOutput dto = new MntiListOutput();
            dto.setMntiName(mntiEntity.getMntiName());
            dto.setMntiListNo(mntiEntity.getMntiListNo());
            dto.setMntiLevel(mntiEntity.getMntiLevel());
            dto.setMntiAdd(mntiEntity.getMntiAdd());
            dto.setHeight(mntiEntity.getMntihigh());
            //dto.setPotoFile(CommonUtil.getImageByMountain(mntiEntity.getMntiListNo()));
            dto.setPotoFile("https://over-the-mountain.site/api/main/" + mntiEntity.getMntiListNo());
            dto.setFamous100(mntiEntity.isFamous100());
            dto.setSeoulTrail(mntiEntity.isSeoulTrail());
            dto.setPhotoSource(mntiEntity.getPhotoSource());
            mntiListOutput.add(dto);
        }

        mntiListOutput.sort(Comparator.comparing(MntiListOutput::getMntiName));
        return mntiListOutput;
    }


    public MntiDetailOutput getMountainDetail(String id) throws Exception {
        MntiDetailOutput mntiDetailOutput = new MntiDetailOutput();
        MntiEntity mntiEntity = mntiRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("산이 존재하지 않습니다."));
        mntiDetailOutput.setMntiName(mntiEntity.getMntiName());
        mntiDetailOutput.setMntiAddress(mntiEntity.getMntiAdd());
        mntiDetailOutput.setPhotoFile("https://over-the-mountain.site/api/main/" + mntiEntity.getMntiListNo());

        List<Course> courses = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource("/mntiCourseData/PMNTN_"+mntiEntity.getMntiName()+"_"+mntiEntity.getMntiListNo()+".json");

        JsonNode rootNode = objectMapper.readTree(resource.getContentAsByteArray());
        JsonNode itemsNode = rootNode.path("features");

        if (itemsNode.isArray()) {
            Set<String> courseNames = new HashSet<>();
            for (JsonNode item : itemsNode) {
                String courseName = item.path("attributes").path("PMNTN_NM").asText();
                if(courseName != null && !courseName.isEmpty() && !courseNames.contains(courseName) && !courseName.equals(" ")) {
                    Course course = new Course();
                    course.setCourseNo(item.path("attributes").path("PMNTN_SN").asText());
                    course.setCourseName(courseName);
                    courseNames.add(courseName);
                    course.setMntiTime(item.path("attributes").path("PMNTN_UPPL").asLong() + item.path("attributes").path("PMNTN_GODN").asLong());
                    course.setMntiLevel(item.path("attributes").path("PMNTN_DFFL").asText());
                    course.setMntiDist(item.path("attributes").path("PMNTN_LT").asText());
                    JsonNode pathsNode = item.path("geometry").path("paths");
                    if (pathsNode.isArray()) {
                        List<List<Coordinate>> paths = new ArrayList<>();
                        for (JsonNode pathNode : pathsNode) {
                            List<Coordinate> path = new ArrayList<>();
                            for (JsonNode coordNode : pathNode) {
                                double[] coordinates = new double[]{coordNode.get(0).asDouble(), coordNode.get(1).asDouble()};
                                path.add(new Coordinate(coordinates));
                            }
                            paths.add(path);
                        }
                        course.setPaths(paths);
                    }
                    if(course.getMntiTime() >= 10) {
                        courses.add(course);
                    }
                }
            }
        }

        //자유코스 추가하기
        Course freeCourse = new Course();
        freeCourse.setCourseNo("free");
        freeCourse.setCourseName("자유코스");

        //TODO : 자유코스인 경우 제일 첫번째 코스의 위치값 넣기
        // TODO : 자유코스랑 일반 코스랑 겹치는 문제 해결하기
        List<List<Coordinate>> paths = new ArrayList<>();
        List<Coordinate> path = new ArrayList<>();
        JsonNode firstItem = itemsNode.get(0);
        JsonNode coordNode = firstItem.path("geometry").path("paths").get(0);
        double[] coordinates = new double[]{
                coordNode.get(0).get(0).asDouble(), coordNode.get(0).get(1).asDouble()
        };
        path.add(new Coordinate(coordinates));
        paths.add(path);

        freeCourse.setPaths(paths);
        courses.add(freeCourse);

        //코스id 오름차순 정렬
        courses.sort(Comparator.comparing(Course::getCourseNo));

        mntiDetailOutput.setContent(rootNode.path("content").asText());
        mntiDetailOutput.setMntiLevel(mntiEntity.getMntiLevel());
        mntiDetailOutput.setCourse(courses);
        mntiDetailOutput.setMntiHigh(mntiEntity.getMntihigh());
        mntiDetailOutput.setFamous100(mntiEntity.isFamous100());
        mntiDetailOutput.setSeoulTrail(mntiEntity.isSeoulTrail());
        mntiDetailOutput.setWebsite(mntiEntity.getWebsite());
        mntiDetailOutput.setPhotoSource(mntiEntity.getPhotoSource());

        //날씨 리스트 가져오기
        mntiDetailOutput.setWeatherList(weatherList);
        return mntiDetailOutput;
    }



    public WeatherResponse get3DayWeather(String localDate, int number) throws IOException {
        return weatherApi.get3DayWeather(localDate, number);
    }

    public WeatherResponse getOtherDayWeather(String localDate, int number) throws IOException {
        return weatherApi.getOtherDayWeather(localDate, number);
    }


    @Scheduled(cron = "0 0 7,19 * * ?")
    public void updateWeatherList() throws IOException, URISyntaxException {
        this.weatherList = weather6DayList();
    }


    public List<WeatherResponse> weather6DayList() throws IOException, URISyntaxException {
        List<WeatherResponse> weatherList = new ArrayList<>();
        String localDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        //오늘 날씨 가져오기
        weatherList.add(get3DayWeather(localDate, 1));

        //1일 뒤 날씨
        weatherList.add(get3DayWeather(localDate, 4));

        //2일 뒤 날씨
        weatherList.add(get3DayWeather(localDate, 7));

        //3일 뒤
        weatherList.add(getOtherDayWeather(localDate, 3));

        //4일 뒤
        weatherList.add(getOtherDayWeather(localDate, 4));

        //5일 뒤
        weatherList.add(getOtherDayWeather(localDate, 5));

        weatherList.sort(Comparator.comparing(WeatherResponse::getDate));

        int i = 0;
        //날짜값 변경해주기
        for(WeatherResponse weatherResponse : weatherList) {

            LocalDate currentDate = LocalDate.now();
            currentDate = currentDate.plusDays(i); // 날짜를 1일 올림

            //weatherResponse.setDate(String.valueOf(currentDate));
            weatherResponse.setDate(currentDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")));

            i++;
        }

        return weatherList;
    }


    public List<CourseResponse> getCourseList(String mountainId) throws IOException {
        List<CourseResponse> courseList = new ArrayList<>();
        MntiEntity mntiEntity = mntiRepository.findById(mountainId).orElseThrow(() -> new EntityNotFoundException("산이 존재하지 않습니다."));

        ClassPathResource resource = new ClassPathResource("/mntiCourseData/PMNTN_"+mntiEntity.getMntiName()+"_"+mntiEntity.getMntiListNo()+".json");
        JsonNode rootNode = objectMapper.readTree(resource.getContentAsByteArray());
        JsonNode itemsNode = rootNode.path("features");

        if (itemsNode.isArray()) {
            Set<String> courseNames = new HashSet<>();
            for (JsonNode item : itemsNode) {
                String courseName = item.path("attributes").path("PMNTN_NM").asText();
                String courseNo = item.path("attributes").path("PMNTN_SN").asText();
                long courseTime =item.path("attributes").path("PMNTN_UPPL").asLong() + item.path("attributes").path("PMNTN_GODN").asLong();
                if(courseName != null && !courseName.isEmpty() && !courseNames.contains(courseName) && !courseName.equals(" ")) {
                    CourseResponse courseResponse = new CourseResponse();
                    courseResponse.setCourseNo(courseNo);
                    courseResponse.setCourseName(courseName);
                    if(courseTime >= 10) {
                        courseList.add(courseResponse);
                    }
                    courseNames.add(courseName);
                    courseMap.put(courseNo, courseName);
                }
            }
        }


        CourseResponse courseResponse = CourseResponse.builder().courseNo("free").courseName("자유코스").build();
        courseList.add(courseResponse);
        return courseList;
    }


    public String getCourseNameByNo(String courseNo) {
        if(Objects.equals(courseNo, "free")) {
            return "자유코스";
        }
        return courseMap.get(courseNo);
    }

}
