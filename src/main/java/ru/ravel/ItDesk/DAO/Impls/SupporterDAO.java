package ru.ravel.ItDesk.DAO.Impls;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.Mappers.SupporterMapper;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Supporter;

@Repository
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class SupporterDAO {

    private final JdbcTemplate jdbcTemplate;

//    @Override
//    public List<User> getAllUser() {
//        return jdbcTemplate.query("select * from user", new UserMapper());
//    }

    public Supporter getUserByLoginAndPasswordOrReturnNull(String login, String password) {
        Supporter supporter;
        try {
            supporter = jdbcTemplate.queryForObject(
                    "SELECT supporters.id, supporters.login, supporters.name  " +
                            "FROM supporters " +
                            "where login like ? and password like ?",
                    new Object[]{login, password},
                    new SupporterMapper()
            );
            return supporter;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}


//    @Autowired
//    private SupporterDAO supporterDAO;
//
//
////    @Override
////    public List<User> getAllUser() {
////        return userDAOInterface.getAllUser();
////    }
//
//    @Override
//    public Client authorizeUser(String login, String password) {
//        return supporterDAO.getUserByLoginAndPasswordOrReturnNull(login, password);
//    }
//
//}
