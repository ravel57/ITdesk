//package ru.ravel.ItDesk.DAO.Impls;
//
//import org.springframework.dao.EmptyResultDataAccessException;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Repository;
//import ru.ravel.ItDesk.DAO.Interfaces.UserDAOInterface;
//import ru.ravel.ItDesk.Mappers.UserMapper;
//import ru.ravel.ItDesk.Models.User;
//
//
//@Repository
//public class UserDAOImpl implements UserDAOInterface {
//    private final JdbcTemplate jdbcTemplate;
//
//    public UserDAOImpl(JdbcTemplate jdbcTemplate) {
//        this.jdbcTemplate = jdbcTemplate;
//    }
//
////    @Override
////    public List<User> getAllUser() {
////        return jdbcTemplate.query("select * from user", new UserMapper());
////    }
//
////    public User getUserByLoginAndPasswordOrReturnNull(String login, String password) {
////        User user;
////        try {
////            user = jdbcTemplate.queryForObject(
////                    "SELECT FROM `it_desk`.users ",
////                    new Object[]{login, password},
////                    new UserMapper()
////            );
////            return user;
////        } catch (EmptyResultDataAccessException e) {
////            return null;
//}
////    }
////}
