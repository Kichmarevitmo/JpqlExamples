package ru.example.mainops;

import org.hibernate.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.example.mainops.model.Avatar;
import ru.example.mainops.model.Student;
import ru.example.mainops.model.Teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static ru.example.core.HibernateUtils.buildSessionFactory;
import static ru.example.core.HibernateUtils.doInSession;
import static ru.example.core.HibernateUtils.doInSessionWithTransaction;

class MainOperationsTest {

    private Avatar avatar;
    private Student student;

    private SessionFactory sf;

    @BeforeEach
    void setUp() {
        avatar = new Avatar(0, "http://any-addr.ru/");
        student = new Student(0, "AnyName", avatar);

        sf = buildSessionFactory(Student.class, Teacher.class, Avatar.class);
        sf.getStatistics().setStatisticsEnabled(true);
    }

    @DisplayName("persist не вставляет сущность в БД без транзакции")
    @Test
    public void shouldNeverPersistEntityToDBWhenTransactionDoesNotExists() {
        doInSession(sf, session -> session.persist(student));

        assertThat(sf.getStatistics().getPrepareStatementCount()).isEqualTo(0);
    }

    @DisplayName("persist вставляет сущность и ее связь в БД при наличии транзакции")
    @Test
    public void shouldNeverEntityWithRelationToDBWhenTransactionExists() {
        doInSessionWithTransaction(sf, session -> session.persist(student));

        assertThat(sf.getStatistics().getPrepareStatementCount()).isEqualTo(2);
    }

    @DisplayName("выкидывает исключение если вставляемая сущность в состоянии detached")
    @Test
    public void shouldThrowExceptionWhenPersistDetachedEntity() {
        var avatar = new Avatar(1L, "http://any-addr.ru/");
        assertThatCode(() ->
                doInSessionWithTransaction(sf, session -> session.persist(avatar))
        ).hasCauseInstanceOf(PersistentObjectException.class);
    }


    @DisplayName("persist выкидывает исключение если вставляемая сущность " +
            "содержит дочернюю в состоянии transient при выключенной каскадной операции PERSIST")
    @Test
    public void shouldThrowExceptionWhenPersistEntityWithChildInTransientStateAndDisabledCascadeOperation() {
        var teacher = new Teacher(0, "AnyName", avatar);
        assertThatCode(() ->
                doInSessionWithTransaction(sf, session -> session.persist(teacher))
        ).hasCauseInstanceOf(TransientObjectException.class);
    }


    @DisplayName("изменения в сущности под управлением контекста попадают в БД " +
            "при закрытии сессии")
    @Test
    public void shouldSaveEntityChangesToDBAfterSessionClosing() {
        var newName = "NameAny";

        doInSessionWithTransaction(sf, session -> {
            session.persist(student);

            // Отключаем dirty checking (одно из двух)
            session.setHibernateFlushMode(FlushMode.MANUAL);
            //session.detach(student);

            student.setName(newName);
            session.flush();
        });

        assertThat(sf.getStatistics().getEntityUpdateCount()).isEqualTo(1);

        doInSessionWithTransaction(sf, session -> {
            var actualStudent = session.find(Student.class, student.getId());
            assertThat(actualStudent.getName()).isEqualTo(newName);
        });
    }


    @DisplayName("merge при сохранении transient сущности работает как persist," +
            "а при сохранении detached делает дополнительный запрос в БД")
    @Test
    public void shouldWorkAsPeЫrsistWhenSaveTransientEntityAndDoAdditionalSelectWhenSaveDetachedEntity() {
        doInSessionWithTransaction(sf, session -> session.merge(avatar));

        assertThat(sf.getStatistics().getEntityInsertCount()).isEqualTo(1);
        assertThat(sf.getStatistics().getEntityLoadCount()).isEqualTo(0);
        assertThat(sf.getStatistics().getEntityUpdateCount()).isEqualTo(0);

        avatar.setId(1L);
        avatar.setPhotoUrl("http://any-addr2.ru/");
        sf.getStatistics().clear();

        doInSessionWithTransaction(sf, session -> session.merge(avatar));

        assertThat(sf.getStatistics().getEntityLoadCount()).isEqualTo(1);
        assertThat(sf.getStatistics().getEntityUpdateCount()).isEqualTo(1);
        assertThat(sf.getStatistics().getEntityInsertCount()).isEqualTo(0);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @DisplayName("при доступе к LAZY полю за пределами сессии выкидывается исключение")
    @Test
    public void shouldThrowExceptionWhenAccessingToLazyField() {
        doInSessionWithTransaction(sf, session -> session.persist(student));

        Student actualStudent;
        try (var session = sf.openSession()) {
            actualStudent = session.find(Student.class, 1L);
        }
        assertThatCode(() -> actualStudent.getAvatar().getPhotoUrl())
                .isInstanceOf(LazyInitializationException.class);
    }

    @DisplayName("find загружает сущность со связями")
    @Test
    public void shouldFindEntityWithChildField() {
        doInSessionWithTransaction(sf, session -> session.persist(student));

        try (var session = sf.openSession()) {
            var actualStudent = session.find(Student.class, 1L);
            assertThat(actualStudent.getAvatar()).isNotNull().isEqualTo(avatar);
        }
    }
}